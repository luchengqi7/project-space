package org.matsim.project.prebookingStudy.jsprit;

import com.graphhopper.jsprit.core.problem.Location;
import com.graphhopper.jsprit.core.problem.VehicleRoutingProblem;
import com.graphhopper.jsprit.core.problem.job.Delivery;
import com.graphhopper.jsprit.core.problem.job.Pickup;
import com.graphhopper.jsprit.core.problem.job.Shipment;
import com.graphhopper.jsprit.core.problem.solution.route.activity.TimeWindow;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleImpl;
import com.graphhopper.jsprit.core.problem.vehicle.VehicleType;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.contrib.drt.run.DrtConfigGroup;
import org.matsim.contrib.drt.run.MultiModeDrtConfigGroup;
import org.matsim.contrib.dvrp.fleet.DvrpVehicleSpecification;
import org.matsim.contrib.dvrp.fleet.FleetReader;
import org.matsim.contrib.dvrp.fleet.FleetSpecification;
import org.matsim.contrib.dvrp.fleet.FleetSpecificationImpl;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;

import java.util.ArrayList;
import java.util.List;
import java.net.URL;

import org.apache.log4j.Logger;

public class MatsimDrtRequest2Jsprit {

    //Config config;
    Scenario scenario;
    final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
    //For switching to the oneTaxi Scenario with prebooking (Can not read from the config directly, so must be specified)
    String dvrpMode;
    int CAPACITY_INDEX;

    final static int MAXIMAL_WAITINGTIME = 60*15;
    final static int MINIMAL_WAITINGTIME = 60*0;

    private static final Logger LOG = Logger.getLogger(MatsimDrtRequest2Jsprit.class);

    // ================ For test purpose
    public static void main(String[] args) {
        MatsimDrtRequest2Jsprit matsimDrtRequest2Jsprit = new MatsimDrtRequest2Jsprit("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_config.xml", "taxi", 0);
    }

    MatsimDrtRequest2Jsprit(String matsimConfig, String dvrpMode, int WEIGHT_INDEX){
        this.dvrpMode = dvrpMode;
        this.CAPACITY_INDEX = WEIGHT_INDEX;

        if("drt".equals(dvrpMode)) {
            URL fleetSpecificationUrl = null;
            Config config = ConfigUtils.loadConfig(matsimConfig, new MultiModeDrtConfigGroup());
            this.scenario = ScenarioUtils.loadScenario(config);
            for (DrtConfigGroup drtCfg : ((MultiModeDrtConfigGroup) config.getModule("multiModeDrt")).getModalElements()) {
                fleetSpecificationUrl = drtCfg.getVehiclesFileUrl(scenario.getConfig().getContext());
            }
            new FleetReader(dvrpFleetSpecification).parse(fleetSpecificationUrl);
        } else if ("oneTaxi".equals(dvrpMode)) {
            Config config = ConfigUtils.loadConfig(matsimConfig);
            this.scenario = ScenarioUtils.loadScenario(config);
            new FleetReader(dvrpFleetSpecification).readFile("/Users/haowu/workspace/playground/matsim-libs/examples/scenarios/dvrp-grid/one_taxi_vehicles.xml");
        }

    }

    // ================ Vehicle Reader
    int matsimVehicleCapacityReader(){
        int capacity = 0;

        if(dvrpFleetSpecification.getVehicleSpecifications().values().stream().map(DvrpVehicleSpecification::getCapacity).distinct().count()==1){
            for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
                capacity = dvrpVehicleSpecification.getCapacity();
                break;
            }
        } else {
            throw new RuntimeException("Dvrp vehicles have different capacity/seats.");
        }

        return capacity;
    }

    VehicleRoutingProblem.Builder matsimVehicleReader(VehicleRoutingProblem.Builder vrpBuilder, VehicleType vehicleType){
        int vehicleCount = 0;

        for(DvrpVehicleSpecification dvrpVehicleSpecification: dvrpFleetSpecification.getVehicleSpecifications().values()) {
            Id<Link> startLinkId = dvrpVehicleSpecification.getStartLinkId();
            double startLinkLocationX;
            double startLinkLocationY;
            if("oneTaxi".equals(dvrpMode)) {
                startLinkLocationX = scenario.getNetwork().getLinks().get(startLinkId).getCoord().getX();
                startLinkLocationY = scenario.getNetwork().getLinks().get(startLinkId).getCoord().getY();
            } else {
                startLinkLocationX = scenario.getNetwork().getLinks().get(startLinkId).getToNode().getCoord().getX();
                startLinkLocationY = scenario.getNetwork().getLinks().get(startLinkId).getToNode().getCoord().getY();
            }
            /*
             * get a vehicle-builder and build a vehicle located at (10,10) with type "vehicleType"
             */
            VehicleImpl.Builder vehicleBuilder = VehicleImpl.Builder.newInstance(dvrpVehicleSpecification.getId().toString());
            vehicleBuilder.setStartLocation(Location.newInstance(startLinkLocationX, startLinkLocationY));
            vehicleBuilder.setEarliestStart(dvrpVehicleSpecification.getServiceBeginTime());
            vehicleBuilder.setLatestArrival(dvrpVehicleSpecification.getServiceEndTime());
            vehicleBuilder.setType(vehicleType);

            VehicleImpl vehicle = vehicleBuilder.build();
            vrpBuilder.addVehicle(vehicle);
            vehicleCount++;
        }

        return vrpBuilder;
    }

    // ================ REQUEST Reader
    VehicleRoutingProblem.Builder matsimRequestReader(VehicleRoutingProblem.Builder vrpBuilder) {
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
                    if(("oneTaxi").equals(dvrpMode)){
                        if (("taxi").equals(leg.getMode())) {
                            legs.add(ii);
                        }
                    } else if(("taxi").equals(dvrpMode)|("drt").equals(dvrpMode)){
                        if ((dvrpMode).equals(leg.getMode())) {
                            legs.add(ii);
                        }
                    }
                }
                ii++;
            }
            for (int legIndex : legs) {
                if (person.getSelectedPlan().getPlanElements().get(legIndex) instanceof Leg) {
                    //the activity before dvrp leg
                    if (person.getSelectedPlan().getPlanElements().get(legIndex - 1) instanceof Activity) {
                        if (!((Activity) person.getSelectedPlan().getPlanElements().get(legIndex - 1)).getType().contains("interaction")) {
                            Activity activity = (Activity) person.getSelectedPlan().getPlanElements().get(legIndex - 1);
                            Id<Link> activityLinkId = activity.getLinkId();
                            if("oneTaxi".equals(dvrpMode)) {
                                pickupLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getCoord().getX();
                                pickupLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getCoord().getY();
                            } else {
                                if(("dummy").equals(activity.getType())){
                                    pickupLocationX = activity.getCoord().getX();
                                    pickupLocationY = activity.getCoord().getY();
                                } else {
                                    pickupLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getX();
                                    pickupLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getY();
                                }
                            }
                            pickupTime = activity.getEndTime().seconds();
                        } else {
                            throw new RuntimeException("Activity before is an 'interaction' activity.");
                        }
                    } else {
                        throw new RuntimeException("Plan element is not the activity before taxi leg.");
                    }

/*                    //dvrp leg
                    Leg leg = (Leg) person.getSelectedPlan().getPlanElements().get(legIndex);
                    pickupTime = leg.getDepartureTime().seconds();
                    //deliveryTime = ((OptionalTime) leg.getAttributes().getAttribute("arr_time")).seconds();
                    //deliveryTime = leg.getDepartureTime().seconds()+leg.getTravelTime().seconds();*/

                    //the activity after dvrp leg
                    if (person.getSelectedPlan().getPlanElements().get(legIndex + 1) instanceof Activity) {
                        if (!((Activity) person.getSelectedPlan().getPlanElements().get(legIndex + 1)).getType().contains("interaction")) {
                            Activity activity = (Activity) person.getSelectedPlan().getPlanElements().get(legIndex + 1);
                            Id<Link> activityLinkId = activity.getLinkId();
                            if("oneTaxi".equals(dvrpMode)) {
                                deliveryLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getCoord().getX();
                                deliveryLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getCoord().getY();
                            } else {
                                if(("dummy").equals(activity.getType())){
                                    deliveryLocationX = activity.getCoord().getX();
                                    deliveryLocationY = activity.getCoord().getY();
                                } else {
                                    deliveryLocationX = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getX();
                                    deliveryLocationY = scenario.getNetwork().getLinks().get(activityLinkId).getToNode().getCoord().getY();
                                }
                            }

                        } else {
                            throw new RuntimeException("Activity after is an 'interaction' activity.");
                        }
                    } else {
                        throw new RuntimeException("Plan element is not the activity after taxi leg.");
                    }
                } else {
                    throw new RuntimeException("Plan element is not leg.");
                }

                //use Shipment to create request for jsprit
                /*
                 *
                 */
                Shipment shipment = Shipment.Builder.newInstance("shipment"+requestCount)
                        //.setName("myShipment")
                        .setPickupLocation(Location.newInstance(pickupLocationX, pickupLocationY)).setDeliveryLocation(Location.newInstance(deliveryLocationX, deliveryLocationY))
                        .addSizeDimension(CAPACITY_INDEX,1)/*.addSizeDimension(1,50)*/
                        //.addRequiredSkill("loading bridge").addRequiredSkill("electric drill")
                        .setPickupServiceTime(60)
                        .setDeliveryServiceTime(60)
                        .setPickupTimeWindow(new TimeWindow(pickupTime, pickupTime + MAXIMAL_WAITINGTIME))
                        //.setDeliveryTimeWindow(new TimeWindow(0, deliveryTime + DILIVERYTOLERANCETIME_OFFSET))
                        //ToDo: Approach1:the deliveryTime - pickupTime is too much!  Approach2:use α(detour facyor) * time travel!
                        //.setMaxTimeInVehicle(deliveryTime - pickupTime /*- 60 - 60*/ - MINIMAL_WAITINGTIME)
                        .setMaxTimeInVehicle(Double.MAX_VALUE)
                        //.setPriority()
                        .build();
                vrpBuilder.addJob(shipment);
                requestCount++;

            }
        }
        //PopulationUtils.writePopulation(scenario.getPopulation(), utils.getOutputDirectory() + "/../pop.xml");

        //return requests
        return vrpBuilder;
    }

}
