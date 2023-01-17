package org.matsim.playground.commonScripts;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.population.PopulationUtils;

public class ExampleGeneratingDrtPlans {
    public static void main(String[] args) {
        // Specify your output path
        String outputPath = "xxx";

        // Read network
        Network network = NetworkUtils.readNetwork("your/network/path");

        // Creating an empty population file
        Config config = ConfigUtils.createConfig();
        Population outputPlans = PopulationUtils.createPopulation(config);
        // Get population factory from that population file (usually, it doesn't matter which population file you get this population factory)
        PopulationFactory populationFactory = outputPlans.getFactory();

        // create a person
        Person person = populationFactory.createPerson(Id.createPersonId("drt_person_x"));

        // create a plan
        Plan plan = populationFactory.createPlan();

        // create an activity
        // option a: from coord
        Coord fromCoord = new Coord(100, 200);  // Generate a Coord
        Activity activity0 = populationFactory.createActivityFromCoord("dummy", fromCoord); // The activity is called "dummy" here. You can simply use this name for DRT plans
        activity0.setEndTime(7200); // Set the end time to the departure time (if there is a leg after this activity)

        // option b: from link id
        Id<Link> toLinkId = Id.createLinkId("12345"); // You can also get a link from the network and then get the id of that link
        Activity activity1 = populationFactory.createActivityFromLinkId("dummy", toLinkId);

        // option c: map the coord to link and use link ID
        Link fromLink = NetworkUtils.getNearestLink(network, fromCoord); // You can write your own script to map the coord to the relevant links (e.g. excluding motorways, tunnels, bridges...)
        Activity activity3 = populationFactory.createActivityFromLinkId("dummy", fromLink.getId());

        // create a leg
        Leg leg = populationFactory.createLeg(TransportMode.drt); // create a drt leg

        // add plan elements (i.e. activity/leg) to the plan. Please use the correct order.
        plan.addActivity(activity0);
        plan.addLeg(leg);
        plan.addActivity(activity1);

        // Add the plan to the person
        person.addPlan(plan);

        // add the person to the population file
        outputPlans.addPerson(person);

        // write out the population file
        PopulationWriter populationWriter = new PopulationWriter(outputPlans);
        populationWriter.write(outputPath);
    }
}
