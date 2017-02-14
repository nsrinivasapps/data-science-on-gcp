/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.training.flights;

import java.util.Map;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.Mean;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.View;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A dataflow pipeline to create the training dataset to predict whether a
 * flight will be delayed by 15 or more minutes. The key thing that this
 * pipeline does is to add the average delays for the from & to airports at this
 * hour to the set of training features.
 * 
 * @author vlakshmanan
 *
 */
public class CreateTrainingDataset {
	@SuppressWarnings("serial")
	public static class ParseFlights extends DoFn<String, Flight> {
		private final PCollectionView<Map<String, String>> traindays;

		public ParseFlights(PCollectionView<Map<String, String>> traindays) {
			super();
			this.traindays = traindays;
		}

		@ProcessElement
		public void processElement(ProcessContext c) throws Exception {
			String line = c.element();
			try {
				String[] fields = line.split(",");
				if (fields[22].length() == 0) {
					return; // delayed/canceled
				}

				Flight f = new Flight();
				f.date = fields[0];

				boolean isTrainDay = c.sideInput(traindays).containsKey(f.date);
				if (!isTrainDay) {
					LOG.debug("Ignoring " + f.date + " as it is not a trainday");
					return;
				}

				f.fromAirport = fields[8];
				f.toAirport = fields[12];
				f.depHour = Integer.parseInt(fields[13]) / 100; // 2358 -> 23
				f.arrHour = Integer.parseInt(fields[21]) / 100;
				f.departureDelay = Double.parseDouble(fields[15]);
				f.taxiOutTime = Double.parseDouble(fields[16]);
				f.distance = Double.parseDouble(fields[26]);
				f.arrivalDelay = Double.parseDouble(fields[22]);
				f.averageDepartureDelay = f.averageArrivalDelay = Double.NaN;
				c.output(f);
			} catch (Exception e) {
				LOG.warn("Malformed line {" + line + "} skipped", e);
			}
		}

	}

	private static final Logger LOG = LoggerFactory.getLogger(CreateTrainingDataset.class);

	public static interface MyOptions extends PipelineOptions {
		@Description("Path of the file to read from")
		@Default.String("/Users/vlakshmanan/data/flights/small.csv")
		String getInput();

		void setInput(String s);

		@Description("Path of the output directory")
		@Default.String("/tmp/output/")
		String getOutput();

		void setOutput(String s);

		@Description("Path of trainday.csv")
		@Default.String("gs://cloud-training-demos/flights/trainday.csv")
		String getTraindayCsvPath();

		void setTraindayCsvPath(String s);
	}

	@SuppressWarnings("serial")
	public static void main(String[] args) {
		MyOptions options = PipelineOptionsFactory.fromArgs(args).withValidation().as(MyOptions.class);
		Pipeline p = Pipeline.create(options);

		// read traindays.csv into memory for use as a side-input
		PCollectionView<Map<String, String>> traindays = getTrainDays(p, options.getTraindayCsvPath());

		PCollection<Flight> flights = p //
				.apply("ReadLines", TextIO.Read.from(options.getInput())) //
				.apply("ParseFlights", ParDo.withSideInputs(traindays).of(new ParseFlights(traindays))) //
		;

		PCollection<KV<String, Double>> delays = flights
				.apply("airport:hour", ParDo.of(new DoFn<Flight, KV<String, Double>>() {

					@ProcessElement
					public void processElement(ProcessContext c) throws Exception {
						Flight f = c.element();
						String key = f.fromAirport + ":" + f.depHour;
						double value = f.departureDelay + f.taxiOutTime;
						c.output(KV.of(key, value));
						key = "arr_" + f.toAirport + ":" + f.date + ":" + f.arrHour;
						value = f.arrivalDelay;
						c.output(KV.of(key, value));
					}

				})) //
				.apply(Mean.perKey());

		delays.apply("DelayToCsv", ParDo.of(new DoFn<KV<String, Double>, String>() {
			@ProcessElement
			public void processElement(ProcessContext c) throws Exception {
				KV<String, Double> kv = c.element();
				if (!kv.getKey().startsWith("arr_")){
					c.output(kv.getKey() + "," + kv.getValue());
				}
			}
		})) //
				.apply("WriteDelays", TextIO.Write.to(options.getOutput() + "delays").withSuffix(".csv"));

		PCollectionView<Map<String, Double>> avgDelay = delays.apply(View.asMap());
		flights = flights.apply("AddDelayInfo", ParDo.withSideInputs(avgDelay).of(new DoFn<Flight, Flight>() {

			@ProcessElement
			public void processElement(ProcessContext c) throws Exception {
				Flight f = c.element().newCopy();
				String key = f.fromAirport + ":" + f.depHour;
				Double delay = c.sideInput(avgDelay).get(key);
				f.averageDepartureDelay = (delay == null)? 0 : delay;
				key = "arr_" + f.toAirport + ":" + f.date + ":" + (f.depHour-1);
				delay = c.sideInput(avgDelay).get(key);
				f.averageArrivalDelay = (delay == null)? 0 : delay;
				c.output(f);
			}

		}));

		flights.apply("ToCsv", ParDo.of(new DoFn<Flight, String>() {
			@ProcessElement
			public void processElement(ProcessContext c) throws Exception {
				Flight f = c.element();
				c.output(f.toTrainingCsv());
			}
		})) //
				.apply("WriteFlights", TextIO.Write.to(options.getOutput() + "flights").withSuffix(".csv"));

		p.run();
	}

	@SuppressWarnings("serial")
	private static PCollectionView<Map<String, String>> getTrainDays(Pipeline p, String path) {
		return p.apply("Read trainday.csv", TextIO.Read.from(path)) //
				.apply("Parse trainday.csv", ParDo.of(new DoFn<String, KV<String, String>>() {
					@ProcessElement
					public void processElement(ProcessContext c) throws Exception {
						String line = c.element();
						String[] fields = line.split(",");
						if (fields.length > 1 && "True".equals(fields[1])) {
							c.output(KV.of(fields[0], "")); // ignore value
						}
					}
				})) //
				.apply("toView", View.asMap());
	}
}