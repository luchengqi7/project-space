package org.matsim.project.drtOperationStudy.scenarioPreparation;

import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Population;
import org.matsim.api.core.v01.population.PopulationWriter;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;
import java.util.Random;

public class DepartureTimeAdjustment {

    public static void main(String[] args) {
        String inputPlansFileString = args[0];
        String outputPlanFileString = args[1];

        Random random = new Random(1234);
        int earliestDepartureTime = 3600;
        int latestDepartureTime = 22 * 3600;
        int duration = latestDepartureTime - earliestDepartureTime;

        Population population = PopulationUtils.readPopulation(inputPlansFileString);
        for (Person person : population.getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                trip.getOriginActivity().setEndTime(random.nextInt(duration) + earliestDepartureTime);
            }
        }

        PopulationWriter populationWriter = new PopulationWriter(population);
        populationWriter.write(outputPlanFileString);
    }
}
