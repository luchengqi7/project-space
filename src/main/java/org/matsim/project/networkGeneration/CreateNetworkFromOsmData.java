package org.matsim.project.networkGeneration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.network.NetworkWriter;
import org.matsim.api.core.v01.network.Node;
import org.matsim.application.MATSimAppCommand;
import org.matsim.application.options.CrsOptions;
import org.matsim.application.options.ShpOptions;
import org.matsim.contrib.osm.networkReader.LinkProperties;
import org.matsim.contrib.osm.networkReader.OsmTags;
import org.matsim.contrib.osm.networkReader.SupersonicOsmNetworkReader;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.network.algorithms.MultimodalNetworkCleaner;
import org.matsim.core.utils.geometry.geotools.MGC;
import org.matsim.core.utils.gis.ShapeFileReader;
import org.opengis.feature.simple.SimpleFeature;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class CreateNetworkFromOsmData implements MATSimAppCommand {
    private static final Logger log = LogManager.getLogger(CreateNetworkFromOsmData.class);

    @CommandLine.Option(names = "--input", description = "input pbf file", required = true)
    private Path input;

    @CommandLine.Option(names = "--urban-area", description = "Path to urban area shape file", defaultValue = "")
    private String urbanAreaPath;

    @CommandLine.Option(names = "--output", description = "output network file", required = true)
    private Path output;

    @CommandLine.Mixin
    private ShpOptions shp = new ShpOptions();
    // To only keep the links within certain area, specify the shapefile

    @CommandLine.Mixin
    private CrsOptions crs = new CrsOptions();
    // Input CRS: WGS84 (EPSG:4326). Target CRS: EPSG:25832

    public static void main(String[] args) {
        new CreateNetworkFromOsmData().execute(args);
    }

    @Override
    public Integer call() throws Exception {
        Network network = new SupersonicOsmNetworkReader.Builder()
                .addOverridingLinkProperties(OsmTags.MOTORWAY, new LinkProperties(1, 2, 120 / 3.6, 3600, true))
                .addOverridingLinkProperties(OsmTags.MOTORWAY_LINK, new LinkProperties(1, 1, 100 / 3.6, 3600, true))
                .addOverridingLinkProperties(OsmTags.TRUNK, new LinkProperties(1, 1, 100 / 3.6, 3600, false))
                .addOverridingLinkProperties(OsmTags.TRUNK_LINK, new LinkProperties(1, 1, 100 / 3.6, 3600, false))
                .addOverridingLinkProperties(OsmTags.PRIMARY, new LinkProperties(1, 1, 80 / 3.6, 1800, false))
                .addOverridingLinkProperties(OsmTags.PRIMARY_LINK, new LinkProperties(1, 1, 80 / 3.6, 1800, false))
                .addOverridingLinkProperties(OsmTags.SECONDARY, new LinkProperties(1, 1, 60 / 3.6, 1200, false))
                .addOverridingLinkProperties(OsmTags.SECONDARY_LINK, new LinkProperties(1, 1, 60 / 3.6, 1200, false))
                .addOverridingLinkProperties(OsmTags.TERTIARY, new LinkProperties(1, 1, 60 / 3.6, 900, false))
                .addOverridingLinkProperties(OsmTags.TERTIARY_LINK, new LinkProperties(1, 1, 60 / 3.6, 900, false))
                .addOverridingLinkProperties(OsmTags.RESIDENTIAL, new LinkProperties(1, 1, 30 / 3.6, 600, false))
                .addOverridingLinkProperties(OsmTags.UNCLASSIFIED, new LinkProperties(1, 1, 30 / 3.6, 600, false))
                .setCoordinateTransformation(crs.getTransformation())
                .setPreserveNodeWithId(id -> id == 2)
                .setAfterLinkCreated((link, osmTags, isReverse) -> link.setAllowedModes(new HashSet<>(List.of(TransportMode.car))))
                .build()
                .read(input.toString());

        if (shp.isDefined()) {
            Set<Link> linkToRemove = new HashSet<>();
            for (Link link : network.getLinks().values()) {
                if (!MGC.coord2Point(link.getToNode().getCoord()).within(shp.getGeometry())) {
                    linkToRemove.add(link);
                }
            }
            linkToRemove.forEach(l -> network.removeLink(l.getId()));
        }

        var cleaner = new MultimodalNetworkCleaner(network);
        cleaner.run(Set.of(TransportMode.car));

        Set<Node> nodesToRemove = new HashSet<>();
        for (Node node : network.getNodes().values()) {
            if (node.getInLinks().size() == 0 && node.getOutLinks().size() == 0) {
                nodesToRemove.add(node);
            }
        }
        nodesToRemove.forEach(n -> network.removeNode(n.getId()));

        log.info("Modifying network speed for urban area...");
        log.info("Loading urban area geometry file");
        List<Geometry> urbanAreaGeometries = new ArrayList<>();
        if (!urbanAreaPath.equals("")) {
            for (SimpleFeature feature : ShapeFileReader.getAllFeatures(urbanAreaPath)) {
                Geometry subArea = (Geometry) feature.getDefaultGeometry();
                if (subArea.isValid()) {
                    urbanAreaGeometries.add(subArea);
                }
            }
        }

        log.info("There are " + network.getLinks().size() + " links to be processed");
        int counter = 0;
        for (Link link : network.getLinks().values()) {
            Point from = MGC.coord2Point(link.getFromNode().getCoord());
            Point to = MGC.coord2Point(link.getToNode().getCoord());
            Point center = MGC.coord2Point(link.getCoord());

            for (Geometry geometry : urbanAreaGeometries) {
                if (from.within(geometry) || to.within(geometry) || center.within(geometry)) {
                    if (link.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.RESIDENTIAL)) {
                        double freeSpeed = 18 / 3.6;
                        link.setFreespeed(freeSpeed);
                    } else if (!link.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.MOTORWAY)
                            && !link.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.MOTORWAY_LINK)
                            && !link.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.TRUNK)
                            && !link.getAttributes().getAttribute(NetworkUtils.TYPE).equals(OsmTags.TRUNK_LINK)) {
                        double freeSpeed = 45 / 3.6;
                        link.setFreespeed(freeSpeed);
                    }
                    break;
                }
            }
            counter++;
            if (counter % 10000 == 0) {
                log.info("Processing: " + counter + "completed");
            }
        }

        new NetworkWriter(network).write(output.toString());
        return 0;
    }
}
