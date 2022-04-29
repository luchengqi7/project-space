package org.matsim.project.prebookingStudy.analysis.ptBenchmark;

import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.PlanElement;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.analysis.DefaultAnalysisMainModeIdentifier;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.router.AnalysisMainModeIdentifier;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

@CommandLine.Command(
        name = "run-pt-baseline",
        description = "run pt baseline"
)
public class RunPtScenario implements MATSimAppCommand {
    @CommandLine.Option(names = "--config", description = "path to config file", required = true)
    private static String configPath;

    @CommandLine.Option(names = "--output", description = "path to config file", required = true)
    private static String outputDirectory;

    public static void main(String[] args) {
        new RunPtScenario().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Config config = ConfigUtils.loadConfig(configPath);
        config.controler().setOutputDirectory(outputDirectory);
        config.planCalcScore().setWriteExperiencedPlans(true);

        Scenario scenario = ScenarioUtils.loadScenario(config);
        modifyPopulation(scenario.getPopulation());

        Controler controler = new Controler(scenario);
        controler.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                install(new SwissRailRaptorModule());
                bind(AnalysisMainModeIdentifier.class).to(DefaultAnalysisMainModeIdentifier.class);
            }
        });
        controler.run();
        return 0;
    }

    /**
     * Set the mode of every trip to PT
     */
    private static void modifyPopulation(Population population) {
        for (Person person : population.getPersons().values()) {
            for (PlanElement pe : person.getSelectedPlan().getPlanElements()) {
                if (pe instanceof Leg) {
                    ((Leg) pe).setMode(TransportMode.pt);
                }
            }
        }
    }
}
