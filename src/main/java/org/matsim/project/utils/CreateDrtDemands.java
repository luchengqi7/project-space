package org.matsim.project.utils;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.router.TripStructureUtils;

import java.util.List;

public class CreateDrtDemands {
	private static final String INPUT_POPULATION = "/Users/luchengqi/Documents/MATSimScenarios/Cottbus/drt-test-plans.xml";
	private static final String OUTPUT_POPULATION = "/Users/luchengqi/Documents/MATSimScenarios/Cottbus/new-drt-test-plans.xml.gz";

	public static void main(String[] args) {
		Population inputPlans = PopulationUtils.readPopulation(INPUT_POPULATION);
		Population outputPlans = PopulationUtils.createPopulation(ConfigUtils.createConfig());
		PopulationFactory populationFactory = inputPlans.getFactory();

		int counter = 0;
		for (Person person : inputPlans.getPersons().values()) {
			List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
			for (TripStructureUtils.Trip trip : trips) {
				Activity orginAct = trip.getOriginActivity();
				Activity destinationAct = trip.getDestinationActivity();
				orginAct.setType("dummy");
				destinationAct.setEndTimeUndefined();
				destinationAct.setType("dummy");

				Person outputPerson = populationFactory.createPerson(Id.createPersonId("drt_person_" + counter));
				Plan plan = populationFactory.createPlan();
				plan.addActivity(orginAct);
				plan.addLeg(populationFactory.createLeg(TransportMode.drt));
				plan.addActivity(destinationAct);
				outputPerson.addPlan(plan);
				outputPlans.addPerson(outputPerson);
				counter++;
			}

		}

		PopulationWriter populationWriter = new PopulationWriter(outputPlans);
		populationWriter.write(OUTPUT_POPULATION);
	}


}
