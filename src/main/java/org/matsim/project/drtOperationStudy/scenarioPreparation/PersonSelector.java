package org.matsim.project.drtOperationStudy.scenarioPreparation;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import picocli.CommandLine;

import java.util.Random;

public class PersonSelector implements MATSimAppCommand {
    @CommandLine.Option(names = "--input", description = "path to input plans file", required = true)
    private String inputPlansPath;

    @CommandLine.Option(names = "--output", description = "output path", required = true)
    private String outputPath;

    @CommandLine.Option(names = "--pct", description = "percentage of persons to keep", required = true)
    private double percentage;

    public static void main(String[] args) {
        new PersonSelector().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Random random = new Random(1234);

        Population population = PopulationUtils.readPopulation(inputPlansPath);
        Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        for (Person person : population.getPersons().values()) {
            if (random.nextDouble() < percentage) {
                outputPlans.addPerson(person);
            }
        }

        PopulationWriter writer = new PopulationWriter(outputPlans);
        writer.write(outputPath);

        return 0;
    }
}
