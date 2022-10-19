package org.matsim.project.drtSchoolTransportStudy.jsprit.prepare;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.contrib.dvrp.fleet.*;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.io.MatsimNetworkReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.geotools.MGC;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@CommandLine.Command(
        name = "run",
        description = "reduce matsim scenario for Jsprit"
)
public class ScenarioReducer implements MATSimAppCommand {

    @CommandLine.Option(names = "--networkInputPath", description = "path to input network", defaultValue = "scenarios/cottbus/network.xml.gz")
    private static Path networkInputPath;

    @CommandLine.Option(names = "--planInputPath", description = "path to input plan", defaultValue = "scenarios/cottbus/drt-test-plans.xml.gz")
    private static Path planInputPath;

    @CommandLine.Option(names = "--testPlanOutputPath", description = "path for saving test plan", defaultValue = "../test/scenarios/vulkaneifel/drt-test-plans.xml.gz")
    private static Path testPlanOutputPath;

    @CommandLine.Option(names = "--dvrpVehicleInputPath", description = "path to input dvrp vehicle fleet", defaultValue = "scenarios/cottbus/drt-vehicles/500-4-seater-drt-vehicles.xml")
    private static Path dvrpVehicleInputPath;

    @CommandLine.Option(names = "--testDvrpVehicleOutputPath", description = "path for saving test dvrp vehicle fleet", defaultValue = "../test/scenarios/vulkaneifel/drt-vehicles/500-4-seater-drt-vehicles.xml.gz")
    private static Path testDvrpVehicleOutputPath;

    //BoundingBox:
    private static final double X_MIN = 451621.;
    private static final double Y_MIN = 5733472.;
    private static final double X_MAX = 455689.;
    private static final double Y_MAX = 5735319.;
/*    private static final double X_MIN = 0.;
    private static final double Y_MIN = 0.;
    private static final double X_MAX = Double.MAX_VALUE;
    private static final double Y_MAX = Double.MAX_VALUE;*/

    private final Logger LOG = LogManager.getLogger(ScenarioReducer.class);

    public static void main(String[] args) {
        new ScenarioReducer().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Geometry boundingBox = createBoundingBox();

        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new MatsimNetworkReader(scenario.getNetwork()).readFile(networkInputPath.toString());
        new PopulationReader(scenario).readFile(planInputPath.toString());
        String populationOutputFile = testPlanOutputPath.toString();
        final FleetSpecification dvrpFleetSpecification = new FleetSpecificationImpl();
        new FleetReader(dvrpFleetSpecification).readFile(dvrpVehicleInputPath.toString());
        String dvrpFleetOutputFile = testDvrpVehicleOutputPath.toString();

        ScenarioReducer scenarioReducer = new ScenarioReducer();
        scenarioReducer.scenarioReducer(boundingBox, scenario, populationOutputFile, dvrpFleetSpecification, dvrpFleetOutputFile);

        return 0;
    }

    private static Geometry createBoundingBox() {
        return new GeometryFactory().createPolygon(new Coordinate[]{
                new Coordinate(X_MIN, Y_MIN), new Coordinate(X_MAX, Y_MIN),
                new Coordinate(X_MAX, Y_MAX), new Coordinate(X_MIN, Y_MAX),
                new Coordinate(X_MIN, Y_MIN)
        });
    }

    private void scenarioReducer(Geometry boundingBox, Scenario scenario, String populationOutputFile, FleetSpecification dvrpFleetSpecification, String dvrpFleetOutputFile){
        Set<Person> personToRemoveList = new HashSet<>();
        List<DvrpVehicleSpecification> vehicles = new ArrayList<>();

        //delete the persons whose activities are not all inside the boundingBox
        List<Coord> coordList = new ArrayList<>();
        //add persons whose activities are not all in boundingBox into List for removing
        for (Person person : scenario.getPopulation().getPersons().values()) {
            boolean ifAllActivityInBoundingBox = false;
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                //check the plan structure
                if (trip.getLegsOnly().size() == 1) {
                } else {
                    throw new RuntimeException("Be careful: There exists a trip has more than one legs!");
                }
                if((("dummy").equals(trip.getOriginActivity().getType()))&(("dummy").equals(trip.getDestinationActivity().getType()))){
                } else {
                    //The first planElement of first agent's selected plan is activity, but not a 'dummy' activity.
                    throw new RuntimeException("This method is not implemented yet!");
                }




                //check if the originActivity and destinationActivity of the person's each trip has the same location, then remove this person form plan
                if(trip.getOriginActivity().getCoord().equals(trip.getDestinationActivity().getCoord())) {
                    personToRemoveList.add(person);
                    break;
                }


                //check if this person's trips contains an activity's location that already included in other person's trip
                if(coordList.contains(trip.getOriginActivity().getCoord())|coordList.contains(trip.getDestinationActivity().getCoord())) {
                    personToRemoveList.add(person);
                    break;
                }

                //query if all activities are inside the boundingBox
                if(!inBound(boundingBox, trip.getOriginActivity().getCoord().getX(), trip.getOriginActivity().getCoord().getY())){
                    ifAllActivityInBoundingBox = false;
                    break;
                } else {
                    if(!inBound(boundingBox, trip.getDestinationActivity().getCoord().getX(), trip.getDestinationActivity().getCoord().getY())){
                        ifAllActivityInBoundingBox = false;
                        break;
                    } else {
                        ifAllActivityInBoundingBox = true;
                    }
                }
            }
            if(!ifAllActivityInBoundingBox) {
                personToRemoveList.add(person);
            } else {
                for (TripStructureUtils.Trip trip : trips) {
                    coordList.add(trip.getOriginActivity().getCoord());
                    coordList.add(trip.getDestinationActivity().getCoord());
                }
            }
        }
        for (Person person : personToRemoveList) {
            scenario.getPopulation().getPersons().values().remove(person);
        }


        //add only the vehicles whose startLink is in boundingBox to List for exporting the new Fleet later
        for (DvrpVehicleSpecification dvrpVehicleSpecification : dvrpFleetSpecification.getVehicleSpecifications().values()) {
            if(inBound(boundingBox, scenario.getNetwork().getLinks().get(dvrpVehicleSpecification.getStartLinkId()))){
                vehicles.add(ImmutableDvrpVehicleSpecification.newBuilder().id(dvrpVehicleSpecification.getId())
                        .startLinkId(dvrpVehicleSpecification.getStartLinkId())
                        .capacity(dvrpVehicleSpecification.getCapacity())
                        .serviceBeginTime(dvrpVehicleSpecification.getServiceBeginTime())
                        .serviceEndTime(dvrpVehicleSpecification.getServiceEndTime())
                        .build());
            }
        }


        //outputs
        new PopulationWriter(scenario.getPopulation()).write(populationOutputFile);
        //new FleetWriter(dvrpFleetSpecification.getVehicleSpecifications().values().stream()).write(dvrpFleetOutputFile);
        new FleetWriter(vehicles.stream()).write(dvrpFleetOutputFile);
    }

    private boolean inBound(Geometry boundingBox, Link link) {
        Point linkCenterAsPoint = MGC.xy2Point(link.getCoord().getX(), link.getCoord().getY());
        return boundingBox.contains(linkCenterAsPoint);
    }
    private boolean inBound(Geometry boundingBox, double x, double y) {
        Point linkCenterAsPoint = MGC.xy2Point(x, y);
        return boundingBox.contains(linkCenterAsPoint);
    }

}
