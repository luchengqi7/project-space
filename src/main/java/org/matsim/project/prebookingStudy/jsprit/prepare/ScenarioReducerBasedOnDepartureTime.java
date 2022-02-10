package org.matsim.project.prebookingStudy.jsprit.prepare;

import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Person;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.router.TripStructureUtils;
import org.matsim.core.scenario.ScenarioUtils;
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
public class ScenarioReducerBasedOnDepartureTime implements MATSimAppCommand {
    @CommandLine.Option(names = "--plan-input-path", description = "path to input plan", defaultValue = "scenarios/vulkaneifel/drt-plans.xml.gz")
    private static Path planInputPath;

    @CommandLine.Option(names = "--plan-output-path", description = "path for saving test plan", defaultValue = "../test/scenarios/vulkaneifel/drt-plans.xml.gz")
    private static Path testPlanOutputPath;

    @CommandLine.Option(names = "--filter-startTime", description = "start time for filtering the requests", defaultValue = "28800")
    private static int filterStartTime;

    @CommandLine.Option(names = "--filter-endTime", description = "end time for filtering the requests", defaultValue = "30600")
    private static int filterEndTime;

    public static void main(String[] args) {
        new ScenarioReducerBasedOnDepartureTime().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
        new PopulationReader(scenario).readFile(planInputPath.toString());
        String populationOutputFile = testPlanOutputPath.toString();

        ScenarioReducerBasedOnDepartureTime scenarioReducerBasedOnDepartureTime = new ScenarioReducerBasedOnDepartureTime();
        scenarioReducerBasedOnDepartureTime.scenarioReducer(scenario, populationOutputFile);

        return 0;
    }

    private void scenarioReducer(Scenario scenario, String populationOutputFile) {
        Set<Person> personToRemoveList = new HashSet<>();
        for (Person person : scenario.getPopulation().getPersons().values()) {
            List<TripStructureUtils.Trip> trips = TripStructureUtils.getTrips(person.getSelectedPlan());
            for (TripStructureUtils.Trip trip : trips) {
                double departureTime = trip.getOriginActivity().getEndTime().seconds();
                if(!(departureTime >= filterStartTime & departureTime <= filterEndTime)){
                    personToRemoveList.add(person);
                }
            }
        }
        for (Person person : personToRemoveList) {
            scenario.getPopulation().getPersons().values().remove(person);
        }

        //outputs
        new PopulationWriter(scenario.getPopulation()).write(populationOutputFile);
    }

}
