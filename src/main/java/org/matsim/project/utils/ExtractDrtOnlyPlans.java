package org.matsim.project.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.MainModeIdentifier;
import org.matsim.core.router.TripStructureUtils;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;

public class ExtractDrtOnlyPlans implements MATSimAppCommand {
    @CommandLine.Option(names = "--input", description = "path to input plans", required = true)
    private Path inputPopulation;

    @CommandLine.Option(names = "--output", description = "Output plans", required = true)
    private Path outputPopulation;

    public static void main(String[] args) {
        new ExtractDrtOnlyPlans().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Population inputPlans = PopulationUtils.readPopulation(inputPopulation.toString());
        Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
        PopulationFactory populationFactory = inputPlans.getFactory();
        MainModeIdentifier mainModeIdentifier = new DefaultAnalysisMainModeIdentifier();

        int counter = 0;
        for (Person person : inputPlans.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                String mode = mainModeIdentifier.identifyMainMode(trip.getTripElements());
                if (mode.equals(TransportMode.drt)) {
                    Activity orginAct = trip.getOriginActivity();
                    Activity destinationAct = trip.getDestinationActivity();

                    Activity act0 = populationFactory.createActivityFromCoord("dummy", orginAct.getCoord());
                    Activity act1 = populationFactory.createActivityFromCoord("dummy", destinationAct.getCoord());
                    act0.setEndTime(orginAct.getEndTime().orElse(0));
                    act1.setEndTimeUndefined();

                    Person outputPerson = populationFactory.createPerson(Id.createPersonId("drt_person_" + counter));
                    Plan plan = populationFactory.createPlan();
                    plan.addActivity(act0);
                    plan.addLeg(populationFactory.createLeg(TransportMode.drt));
                    plan.addActivity(act1);
                    outputPerson.addPlan(plan);
                    outputPlans.addPerson(outputPerson);
                    counter++;
                }
            }
        }
        PopulationWriter populationWriter = new PopulationWriter(outputPlans);
        populationWriter.write(outputPopulation.toString());
        return 0;
    }
}
