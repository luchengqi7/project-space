package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.AbstractJob;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.job.Delivery;
import com.graphhopper.jsprit.core.problem.job.Pickup;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;

public class MatsimDrtRequest2Jsprit {

    int WEIGHT_INDEX;
    final static int MAXIMAL_WAITINGTIME = 60*15;
    final static int MINIMAL_WAITINGTIME = 60*0;
    final static int DILIVERYTOLERANCETIME_OFFSET = 60*15;
    static List<Shipment> shipmentsList = new ArrayList<>();
    static List<Service> servicesList = new ArrayList<>();
    final static String configPath = "/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml";

    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit(0);
        List<Shipment> shipmentsList_new = (List<Shipment>) matsimDrtRequest2Jsprit.matsimRequestReader("other");
        System.out.println(shipmentsList_new);
    }

    MatsimDrtRequest2Jsprit(int weightIndex){
        this.WEIGHT_INDEX= weightIndex;
    }

    public List<? extends AbstractJob> matsimRequestReader(String feedType) {
        Config config = ConfigUtils.loadConfig(configPath);
        Scenario scenario = ScenarioUtils.loadScenario(config);
        new PopulationReader(scenario);
/*        Config config = ConfigUtils.createConfig();
    Scenario scenario = ScenarioUtils.loadScenario(config);
    new PopulationReader(scenario).readFile("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_population.xml");*/

        int requestCount = 0;
        double pickupTime;
        double deliveryTime;
        double pickupLocationX;
        double pickupLocationY;
        double deliveryLocationX;
        double deliveryLocationY;
        for (Person person : scenario.getPopulation().getPersons().values()) {
/*            person.getSelectedPlan()
            .getAttributes()
            .putAttribute(PreplanningEngine.PREBOOKING_OFFSET_ATTRIBUTE_NAME, 900.);*/

            List<Integer> legs = new ArrayList<>();
            int ii = 0;
            for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
                if (pe instanceof Leg) {
                    Leg leg = (Leg) pe;
                    if (("taxi").equals(leg.getMode())) {
                        legs.add(ii);
                    }
                }
                ii++;
            }
            for (int legIndex : legs) {
                if (person.getSelectedPlan().getPlanElements().get(legIndex) instanceof Leg) {
                    //the activity before taxi leg
                    if (person.getSelectedPlan().getPlanElements().get(legIndex - 1) instanceof Activity) {
                        if (!((Activity) person.getSelectedPlan().getPlanElements().get(legIndex - 1)).getType().contains("interaction")) {
                            Activity activity = (Activity) person.getSelectedPlan().getPlanElements().get(legIndex - 1);
                            Id<Link> activityLinkId = activity.getLinkId();
                            pickupLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getX();
                            pickupLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getY();
                        } else {
                            throw new RuntimeException("Activity before is an 'interaction' activity.");
                        }
                    } else {
                        throw new RuntimeException("Plan element is not the activity before taxi leg.");
                    }

                    //taxi leg
                    Leg leg = (Leg) person.getSelectedPlan().getPlanElements().get(legIndex);
                    pickupTime = leg.getDepartureTime().seconds();
                    //ToDo: check
                    //deliveryTime = ((OptionalTime) leg.getAttributes().getAttribute("arr_time")).seconds();
                    deliveryTime = leg.getDepartureTime().seconds()+leg.getTravelTime().seconds();

                    //the activity after taxi leg
                    if (person.getSelectedPlan().getPlanElements().get(legIndex + 1) instanceof Activity) {
                        if (!((Activity) person.getSelectedPlan().getPlanElements().get(legIndex + 1)).getType().contains("interaction")) {
                            Activity activity = (Activity) person.getSelectedPlan().getPlanElements().get(legIndex + 1);
                            Id<Link> activityLinkId = activity.getLinkId();
                            deliveryLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getX();
                            deliveryLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getY();
                        } else {
                            throw new RuntimeException("Activity after is an 'interaction' activity.");
                        }
                    } else {
                        throw new RuntimeException("Plan element is not the activity after taxi leg.");
                    }
                } else {
                    throw new RuntimeException("Plan element is not leg.");
                }

                //create request for jsprit
                if ("useService".equals(feedType)) {
                    requestCount = createServices(requestCount, pickupLocationX, pickupLocationY, pickupTime, deliveryTime, deliveryLocationX, deliveryLocationY);
                } else if("useShipment".equals(feedType)) {
                    //create Shipment
                    Shipment shipment = createShipment(requestCount, pickupLocationX, pickupLocationY, pickupTime, deliveryTime, deliveryLocationX, deliveryLocationY);

                    requestCount++;
                    shipmentsList.add(shipment);
                } else {
                    throw new RuntimeException("feedType can be 'useService' or 'useShipment'.");
                }

            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");

        //return requests
        if ("useService".equals(feedType)) {
            return servicesList;
        } else if("useShipment".equals(feedType)) {
            return shipmentsList;
        } else {
            throw new RuntimeException("feedType can be 'useService' or 'useShipment'.");
        }
    }

    private int createServices(int requestCount, double pickupLocationX, double pickupLocationY, double pickupTime, double deliveryTime, double deliveryLocationX, double deliveryLocationY) {
        String requestId = Integer.toString(requestCount);
        Pickup pickup = Pickup.Builder.newInstance("pickup"+requestId)
            .addSizeDimension(WEIGHT_INDEX, 1)
            .setLocation(Location.newInstance(pickupLocationX, pickupLocationY))
            .setServiceTime(60)
            .setTimeWindow(new TimeWindow(pickupTime, pickupTime + MAXIMAL_WAITINGTIME))
            .build();
        Delivery delivery = Delivery.Builder.newInstance("delivery"+requestId)
            .addSizeDimension(WEIGHT_INDEX, 1)
            .setLocation(Location.newInstance(deliveryLocationX, deliveryLocationY))
            .setServiceTime(60)
            .setTimeWindow(new TimeWindow(0, deliveryTime + DILIVERYTOLERANCETIME_OFFSET))
            .build();

        requestCount++;
        servicesList.add(pickup);
        servicesList.add(delivery);
        return requestCount;
    }

    private Shipment createShipment(int requestCount, double pickupLocationX, double pickupLocationY, double pickupTime, double deliveryTime, double deliveryLocationX, double deliveryLocationY) {

        String shipmentId = Integer.toString(requestCount);
        return Shipment.Builder.newInstance("shipment"+shipmentId)
            //.setName("myShipment")
            .setPickupLocation(Location.newInstance(pickupLocationX, pickupLocationY)).setDeliveryLocation(Location.newInstance(deliveryLocationX, deliveryLocationY))
            .addSizeDimension(WEIGHT_INDEX,1)/*.addSizeDimension(1,50)*/
            //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
            .setPickupServiceTime(60)
            .setDeliveryServiceTime(60)
            .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + MAXIMAL_WAITINGTIME))
            //.setDeliveryTimeWindow(new TimeWindow(0, deliveryTime + DILIVERYTOLERANCETIME_OFFSET))
            //ToDo: Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour facyor) * time travel!
            .setMaxTimeInVehicle(deliveryTime - pickupTime /*- 60 - 60*/ - MINIMAL_WAITINGTIME)
            //.setPriority()
            .build();
    }

}
