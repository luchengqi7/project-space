package org.matsim.project.drtRequestPatternIdentification.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.*;

public class RunDownSamplePopulation implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(RunDownSamplePopulation.class);

    @CommandLine.Parameters(paramLabel = "INPUT", arity = "1", description = "Path to population")
    private Path input;

    @CommandLine.Option(names = "--sample-size", description = "Sample size of the given input data in (0, 1]", required = true)
    private double sampleSize;

    @CommandLine.Option(names = "--samples", description = "Desired down-sampled sizes in (0, 1]", arity = "1..*", required = true)
    private List<Double> samples;

    @CommandLine.Option(names = "--seed", description = "random seed for down sampling", defaultValue = "4711")
    private long seed;

    public static void main(String[] args) {
        new RunDownSamplePopulation().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Population population = PopulationUtils.readPopulation(input.toString());
        samples.sort(Comparator.comparingDouble(Double::doubleValue).reversed());
        // original prefix
        String orig = String.format("%dpct", Math.round(sampleSize * 100));

        for (Double sample : samples) {
            // down-sample previous samples
            sampleDownPopulation(population, sample / sampleSize, seed);
            sampleSize = sample;

            String path;
            double outputPct = sampleSize * 100;
            if (input.toString().contains(orig)) {
                if (outputPct % 1 == 0) {
                    path = input.toString().replace(orig, String.format("%dpct-seed-%d", Math.round(outputPct), seed));
                } else {
                    path = input.toString().replace(orig, String.format("%spct-seed-%d", outputPct, seed));
                }
            } else {
                if (outputPct % 1 == 0) {
                    path = input.toString().replace(".xml", String.format("-%dpct-seed-%d.xml", Math.round(outputPct), seed));
                } else {
                    path = input.toString().replace(".xml", String.format("-%spct-seed-%d.xml", outputPct, seed));
                }
            }
            log.info("Writing {} sample to {}", sampleSize, path);

            PopulationUtils.writePopulation(population, path);
        }
        return 0;
    }

    private void sampleDownPopulation(Population population, double sample, long seed) {
        log.info("population size before downsampling=" + population.getPersons().size());
        Random random = new Random(seed);
        int toRemove = (int) ((1 - sample) * population.getPersons().size());
        List<Id<Person>> personList = new ArrayList<>(population.getPersons().keySet());
        Collections.shuffle(personList, random);
        for (int i = 0; i < toRemove; i++) {
            population.removePerson(personList.get(i));
        }
        log.info("population size after downsampling=" + population.getPersons().size());
    }
}
