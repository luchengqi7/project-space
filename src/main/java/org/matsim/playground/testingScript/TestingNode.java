package org.matsim.playground.testingScript;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.scenario.ScenarioUtils;

public class TestingNode {
    public static void main(String[] args) {
        int time = (int) (System.currentTimeMillis() / 1000);
        System.out.println("Current time is " + time);
        Network network1 = NetworkUtils.readNetwork("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/vulkaneifel-v1.0-network.xml.gz");
        Coord coord = new Coord(333509.84287451406, 5578818.032624912);
        Link link = NetworkUtils.getNearestLink(network1, coord);
        System.out.println(link.getId().toString());

        Link linkExact = NetworkUtils.getNearestLinkExactly(network1, coord);
        System.out.println("Exact link:" + linkExact.getId().toString());

        Coord coord2 = linkExact.getToNode().getCoord();
        Link link2 = NetworkUtils.getNearestLinkExactly(network1, coord2);
        System.out.println(link2.getId().toString());

        Config config = ConfigUtils.loadConfig("/Users/luchengqi/Documents/MATSimScenarios/Vulkaneifel/drt-prebooking-study/scenarios/input/vulkaneifel-v1.0-school-children.config.xml");
        Scenario scenario = ScenarioUtils.loadScenario(config);
        Network network2 = scenario.getNetwork();
        Link link3 = NetworkUtils.getNearestLink(network2, coord);
        System.out.println(link3.getId().toString());
    }

}
