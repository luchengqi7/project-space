package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.AbstractJob;
import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Delivery;
import com.graphhopper.jsprit.core.problem.job.Pickup;
import com.graphhopper.jsprit.core.problem.job.Service;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.Vehicle;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.vehicles.VehicleType;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MatsimDrtRequest2Jsprit {

    final static List<Vehicle> vehicleList = new ArrayList<>();
    final static List<Shipment> shipmentsList = new ArrayList<>();
    final static List<Service> servicesList = new ArrayList<>();
    String matsimConfigPath;
    String dvrpMode;
    int WEIGHT_INDEX;

    final static int MAXIMAL_WAITINGTIME = 60*15;
    final static int MINIMAL_WAITINGTIME = 60*0;

    // ================ For test purpose
    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", "taxi", 0);
        List<Shipment> shipmentsList_new = (List<Shipment>) matsimDrtRequest2Jsprit.matsimRequestReader("shipment", new VehicleRoutingProblem.Builder());
        System.out.println(shipmentsList_new);
    }

    MatsimDrtRequest2Jsprit(String matsimConfigPath, String dvrpMode, int WEIGHT_INDEX){
        this.matsimConfigPath = matsimConfigPath;
        this.dvrpMode = dvrpMode;
        this.WEIGHT_INDEX= WEIGHT_INDEX;
    }

    // ================ Vehicle Reader
    int matsimVehicleCapacityReader(String dvrpVehiclePath){
        int capacity = 0;

        //load dvrp vehicle into fleetReader
        FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
        if(isHttpUrl(dvrpVehiclePath)){
            //support read fleet file using URL
            try {
                new FleetReader(dvrpFleetSpecification).parse(new URL(dvrpVehiclePath));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        } else {
            new FleetReader(dvrpFleetSpecification).readFile(dvrpVehiclePath);
        }

        if(dvrpFleetSpecification.getVehicleSpecifications().values().stream().map(DvrpVehicleSpecification::getCapacity).distinct().count()==1){
            //ToDo: Debug
            //dvrpFleetSpecification.getVehicleSpecifications().values().stream().map(o -> capacity==o.getCapacity());
            capacity = 8;
        } else {
            throw new RuntimeException("Dvrp vehicles have different capacity/seats.");
        }

        return capacity;
    }

    List<Vehicle> matsimVehicleReader(String dvrpVehiclePath){
        //load dvrp vehicle into fleetReader
        FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
        if(isHttpUrl(dvrpVehiclePath)){
            //support read fleet file using URL
            try {
                new FleetReader(dvrpFleetSpecification).parse(new URL(dvrpVehiclePath));
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        } else {
            new FleetReader(dvrpFleetSpecification).readFile(dvrpVehiclePath);
        }

        for(DvrpVehicleSpecification dvrpVehicleSpecification: dvrpFleetSpecification.getVehicleSpecifications().values()) {

        }

        return vehicleList;
    }
    /**
     * determine if a string is a URL
     * @param urls: URL
     * @return true: URL, false: not URL
     */
    public static boolean isHttpUrl(String urls) {
        boolean isurl = false;
        //set regular expressions
        String regex = "(((https|http)?://)?([a-z0-9]+[.])|(www.))"
                + "\\w+[.|\\/]([a-z0-9]{0,})?[[.]([a-z0-9]{0,})]+((/[\\S&&[^,;\u4E00-\u9FA5]]+)+)?([.][a-z0-9]{0,}+|/?)";
        //compare
        Pattern pat = Pattern.compile(regex.trim());
        Matcher mat = pat.matcher(urls.trim());
        //determine if it matches
        isurl = mat.matches();
        if (isurl) {
            isurl = true;
        }
        return isurl;
    }

    // ================ REQUEST Reader
    VehicleRoutingProblem.Builder matsimRequestReader(String feedType, VehicleRoutingProblem.Builder vrpBuilder) {
        Config config = ConfigUtils.loadConfig(matsimConfigPath);
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
                    vrpBuilder = createServices(requestCount, pickupLocationX, pickupLocationY, pickupTime, deliveryTime, deliveryLocationX, deliveryLocationY, vrpBuilder);
                    requestCount++;
                } else if("useShipment".equals(feedType)) {
                    //create Shipment
                    Shipment shipment = createShipment(requestCount, pickupLocationX, pickupLocationY, pickupTime, deliveryTime, deliveryLocationX, deliveryLocationY);
                    vrpBuilder.addJob(shipment);
                    requestCount++;
                } else {
                    throw new RuntimeException("feedType can be 'useService' or 'useShipment'.");
                }

            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");

        //return requests
        return vrpBuilder;
    }

    private VehicleRoutingProblem.Builder createServices(int requestCount, double pickupLocationX, double pickupLocationY, double pickupTime, double deliveryTime, double deliveryLocationX, double deliveryLocationY, VehicleRoutingProblem.Builder vrpBuilder) {
        int DILIVERYTOLERANCETIME_OFFSET = 60*15;

        String requestId = Integer.toString(requestCount);
        /*
         * build services at the required locations, each with a capacity-demand of 1.
         */
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

        vrpBuilder.addJob(pickup);
        vrpBuilder.addJob(delivery);
        return vrpBuilder;
    }

    private Shipment createShipment(int requestCount, double pickupLocationX, double pickupLocationY, double pickupTime, double deliveryTime, double deliveryLocationX, double deliveryLocationY) {

        String shipmentId = Integer.toString(requestCount);
        /*
         *
         */
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
