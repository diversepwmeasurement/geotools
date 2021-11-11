/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2017, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.ows.wmts.client;

import static org.geotools.tile.impl.ScaleZoomLevelMatcher.getProjectedEnvelope;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.http.HTTPClient;
import org.geotools.http.HTTPClientFinder;
import org.geotools.ows.wms.CRSEnvelope;
import org.geotools.ows.wmts.WMTSHelper;
import org.geotools.ows.wmts.WMTSSpecification;
import org.geotools.ows.wmts.model.TileMatrix;
import org.geotools.ows.wmts.model.TileMatrixLimits;
import org.geotools.ows.wmts.model.TileMatrixSet;
import org.geotools.ows.wmts.model.TileMatrixSetLink;
import org.geotools.ows.wmts.model.WMTSLayer;
import org.geotools.ows.wmts.model.WMTSServiceType;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.tile.Tile;
import org.geotools.tile.TileFactory;
import org.geotools.tile.TileIdentifier;
import org.geotools.tile.TileService;
import org.geotools.tile.impl.ScaleZoomLevelMatcher;
import org.geotools.tile.impl.ZoomLevel;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Envelope;
import org.opengis.geometry.BoundingBox;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.cs.AxisDirection;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

/**
 * A tile service for a single matrix set of a WMTS servers.
 *
 * <p>An instance of this class is tied to a single layer, style and matrixset.
 *
 * <p>Use WebMapTileServer to negotiate capabilities, or specifying other properties
 *
 * @author ian
 * @author Emanuele Tajariol (etj at geo-solutions dot it)
 */
public class WMTSTileService extends TileService {

    protected static final Logger LOGGER = Logging.getLogger(WMTSTileService.class);

    public static final String EXTRA_HEADERS = "HEADERS";

    private static final TileFactory tileFactory = new WMTSTileFactory();

    private String tileMatrixSetName = "";

    private double[] scaleList;

    private TileMatrixSet matrixSet;

    private final WMTSLayer layer;

    private ReferencedEnvelope envelope;

    private final TileURLBuilder urlBuilder;

    private Map<String, Object> extrainfo = new HashMap<>();

    /**
     * create a service directly with out parsing the capabilities again.
     *
     * <p>This constructor should not be used. A proper formatted templateURL should be used
     * instead.
     *
     * @param templateURL - where to ask for tiles
     * @param type - KVP or REST
     * @param layer - the layer to request
     * @param styleName - name of the style to use?
     * @param tileMatrixSet - the tile matrix set to use
     */
    public WMTSTileService(
            String templateURL,
            WMTSServiceType type,
            WMTSLayer layer,
            String styleName,
            TileMatrixSet tileMatrixSet) {
        this(templateURL, type, layer, styleName, tileMatrixSet, HTTPClientFinder.createClient());
    }

    /**
     * create a service directly with out parsing the capabilities again.
     *
     * <p>This constructor should not be used. A proper formatted templateURL should be used
     * instead.
     *
     * @param templateURL - where to ask for tiles
     * @param type - KVP or REST
     * @param layer - layer to request
     * @param styleName - name of the style to use?
     * @param tileMatrixSet - matrixset
     * @param client - HttpClient instance to use for Tile requests.
     */
    public WMTSTileService(
            String templateURL,
            WMTSServiceType type,
            WMTSLayer layer,
            String styleName,
            TileMatrixSet tileMatrixSet,
            HTTPClient client) {
        this(
                prepareWithLayerStyleTileMatrixSet(
                        templateURL,
                        type,
                        layer.getName(),
                        styleName,
                        tileMatrixSet.getIdentifier()),
                layer,
                tileMatrixSet,
                client);
    }

    private static String prepareWithLayerStyleTileMatrixSet(
            String templateURL,
            WMTSServiceType type,
            String layerName,
            String styleName,
            String tileMatrixSet) {
        switch (type) {
            case KVP:
                return WMTSHelper.appendQueryString(
                        templateURL,
                        WMTSSpecification.GetTileRequest.getKVPparams(
                                layerName, styleName, tileMatrixSet, "image/png"));
            case REST:
                templateURL = WMTSHelper.replaceToken(templateURL, "layer", layerName);
                templateURL = WMTSHelper.replaceToken(templateURL, "style", styleName);
                templateURL = WMTSHelper.replaceToken(templateURL, "tilematrixset", tileMatrixSet);
                return templateURL;
            default:
                throw new IllegalArgumentException("Unexpected WMTS Service type " + type);
        }
    }

    /**
     * Creates a WMTSTileService based on the templateURL. Using extent given by layer and
     * tileMatrixSet.
     *
     * @param templateURL
     * @param layer
     * @param tileMatrixSet
     */
    public WMTSTileService(String templateURL, WMTSLayer layer, TileMatrixSet tileMatrixSet) {
        this(templateURL, layer, tileMatrixSet, HTTPClientFinder.createClient());
    }

    /**
     * create a service directly with out parsing the capabilties again.
     *
     * @param templateURL - where to ask for tiles
     * @param layer - layer to request
     * @param tileMatrixSet - matrixset
     * @param client - HttpClient instance to use for Tile requests.
     */
    public WMTSTileService(
            String templateURL, WMTSLayer layer, TileMatrixSet tileMatrixSet, HTTPClient client) {
        super("wmts", templateURL, client);
        Objects.requireNonNull(layer, "Layer must be non-null.");
        Objects.requireNonNull(layer.getLatLonBoundingBox(), "Layer must have a BoundingBox.");
        this.layer = layer;
        this.tileMatrixSetName = tileMatrixSet.getIdentifier();
        this.envelope = new ReferencedEnvelope(layer.getLatLonBoundingBox());
        this.scaleList = buildScaleList(tileMatrixSet);
        this.urlBuilder = new TileURLBuilder(templateURL);
        setMatrixSet(tileMatrixSet);
    }

    private static double[] buildScaleList(TileMatrixSet tileMatrixSet) {

        double[] scaleList = new double[tileMatrixSet.size()];
        int j = 0;
        for (TileMatrix tm : tileMatrixSet.getMatrices()) {
            scaleList[j++] = tm.getDenominator();
        }

        return scaleList;
    }

    protected ReferencedEnvelope getReqExtentInTileCrs(ReferencedEnvelope requestedExtent) {

        CoordinateReferenceSystem reqCrs = requestedExtent.getCoordinateReferenceSystem();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(
                    "orig request bbox :"
                            + requestedExtent
                            + " "
                            + reqCrs.getCoordinateSystem().getAxis(0).getDirection()
                            + " ("
                            + reqCrs.getName()
                            + ")");
        }

        ReferencedEnvelope reqExtentInTileCrs = null;
        for (CRSEnvelope layerEnv : layer.getLayerBoundingBoxes()) {
            if (CRS.equalsIgnoreMetadata(reqCrs, layerEnv.getCoordinateReferenceSystem())) {
                // crop req extent according to layer bbox
                requestedExtent = requestedExtent.intersection(new ReferencedEnvelope(layerEnv));
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Layer CRS match: cropping request bbox :" + requestedExtent);
                }
                break;
            } else {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine(
                            "Layer CRS not matching: "
                                    + "req:"
                                    + reqCrs.getName()
                                    + " cov:"
                                    + layerEnv.getCoordinateReferenceSystem().getName());
                }
            }
        }

        CoordinateReferenceSystem tileCrs = this.matrixSet.getCoordinateReferenceSystem();

        if (!CRS.equalsIgnoreMetadata(tileCrs, requestedExtent.getCoordinateReferenceSystem())) {
            try {
                reqExtentInTileCrs = requestedExtent.transform(tileCrs, true);
            } catch (TransformException | FactoryException ex) {
                LOGGER.log(
                        Level.WARNING,
                        "Requested extent can't be projected to tile CRS ("
                                + reqCrs.getCoordinateSystem().getName()
                                + " -> "
                                + tileCrs.getCoordinateSystem().getName()
                                + ") :"
                                + ex.getMessage());

                // maybe the req area is too wide for the data; let's try an
                // inverse transformation
                try {
                    ReferencedEnvelope covExtentInReqCrs = envelope.transform(reqCrs, true);
                    requestedExtent = requestedExtent.intersection(covExtentInReqCrs);

                } catch (TransformException | FactoryException ex2) {
                    LOGGER.log(Level.WARNING, "Incompatible CRS: " + ex2.getMessage());
                    return null; // should throw
                }
            }
        } else {
            reqExtentInTileCrs = requestedExtent;
        }

        if (reqExtentInTileCrs == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "Requested extent not in tile CRS range");
            return null;
        }

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "tile crs req bbox :"
                            + reqExtentInTileCrs
                            + " "
                            + reqExtentInTileCrs
                                    .getCoordinateReferenceSystem()
                                    .getCoordinateSystem()
                                    .getAxis(0)
                                    .getDirection()
                            + " ("
                            + reqExtentInTileCrs.getCoordinateReferenceSystem().getName()
                            + ")");
        }

        ReferencedEnvelope coverageEnvelope = getBounds();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "coverage bbox :"
                            + coverageEnvelope
                            + " "
                            + coverageEnvelope
                                    .getCoordinateReferenceSystem()
                                    .getCoordinateSystem()
                                    .getAxis(0)
                                    .getDirection()
                            + " ("
                            + coverageEnvelope.getCoordinateReferenceSystem().getName()
                            + ")");
        }

        ReferencedEnvelope requestEnvelopeWGS84;

        boolean sameCRS =
                CRS.equalsIgnoreMetadata(
                        coverageEnvelope.getCoordinateReferenceSystem(),
                        reqExtentInTileCrs.getCoordinateReferenceSystem());
        if (sameCRS) {
            if (!coverageEnvelope.intersects((BoundingBox) reqExtentInTileCrs)) {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(Level.FINE, "Extents do not intersect (sameCRS))");
                return null;
            }
        } else {
            ReferencedEnvelope dataEnvelopeWGS84;
            try {
                dataEnvelopeWGS84 = coverageEnvelope.transform(DefaultGeographicCRS.WGS84, true);

                requestEnvelopeWGS84 = requestedExtent.transform(DefaultGeographicCRS.WGS84, true);

                if (!dataEnvelopeWGS84.intersects((BoundingBox) requestEnvelopeWGS84)) {
                    if (LOGGER.isLoggable(Level.FINE))
                        LOGGER.log(Level.FINE, "Extents do not intersect");
                    return null;
                }
            } catch (TransformException | FactoryException e) {
                throw new RuntimeException(e);
            }
        }

        return reqExtentInTileCrs;
    }

    @Override
    public Set<Tile> findTilesInExtent(
            ReferencedEnvelope requestedExtent,
            double scaleFactor,
            boolean recommendedZoomLevel,
            int maxNumberOfTiles) {

        Set<Tile> ret = Collections.emptySet();

        ReferencedEnvelope reqExtentInTileCrs = getReqExtentInTileCrs(requestedExtent);
        if (reqExtentInTileCrs == null) {
            if (LOGGER.isLoggable(Level.FINE))
                LOGGER.log(Level.FINE, "No valid extents, no Tiles will be returned.");
            return ret;
        }

        WMTSTileFactory tileFactory = (WMTSTileFactory) getTileFactory();

        ScaleZoomLevelMatcher zoomLevelMatcher = null;
        try {
            zoomLevelMatcher =
                    getZoomLevelMatcher(
                            reqExtentInTileCrs,
                            matrixSet.getCoordinateReferenceSystem(),
                            scaleFactor);

        } catch (FactoryException | TransformException e) {
            throw new RuntimeException(e);
        }

        int zl = getZoomLevelFromMapScale(zoomLevelMatcher, scaleFactor);
        WMTSZoomLevel zoomLevel = tileFactory.getZoomLevel(zl, this);
        long maxNumberOfTilesForZoomLevel = zoomLevel.getMaxTileNumber();

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "Zoom level:"
                            + zl
                            + "["
                            + zoomLevel.getMaxTilePerColNumber()
                            + " x "
                            + zoomLevel.getMaxTilePerRowNumber()
                            + "]");
        }

        Set<Tile> tileList =
                new HashSet<>((int) Math.min(maxNumberOfTiles, maxNumberOfTilesForZoomLevel));

        double ulLon, ulLat;
        // Let's get upper-left corner coords
        CRS.AxisOrder aorder = CRS.getAxisOrder(reqExtentInTileCrs.getCoordinateReferenceSystem());
        switch (aorder) {
            case EAST_NORTH:
                ulLon = reqExtentInTileCrs.getMinX();
                ulLat = reqExtentInTileCrs.getMaxY();
                break;
            case NORTH_EAST:
                if (LOGGER.isLoggable(Level.FINE)) LOGGER.log(Level.FINE, "Inverted tile coords!");
                ulLon = reqExtentInTileCrs.getMinY();
                ulLat = reqExtentInTileCrs.getMaxX();
                break;
            default:
                LOGGER.log(Level.WARNING, "unexpected axis order " + aorder);
                return ret;
        }

        final TileMatrixLimits limits = getLimits(getMatrixSetLink(), getMatrixSet(), zl);

        // The first tile which covers the upper-left corner
        // constrained to limits
        TileIdentifier upperLeft = identifyUpperLeftTile(ulLon, ulLat, zoomLevel);

        if (upperLeft == null) {
            if (LOGGER.isLoggable(Level.INFO)) {
                LOGGER.log(
                        Level.INFO,
                        "First tile not available at x:"
                                + reqExtentInTileCrs.getMinX()
                                + " y:"
                                + reqExtentInTileCrs.getMaxY()
                                + " at "
                                + zoomLevel);
            }

            return ret;
        }

        Tile firstTile = obtainTile(upperLeft);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.log(
                    Level.FINE,
                    "Adding first tile "
                            + firstTile.getId()
                            + " "
                            + firstTile.getExtent()
                            + " ("
                            + firstTile.getExtent().getCoordinateReferenceSystem().getName()
                            + ")");
        }
        tileList.add(firstTile);

        Tile firstTileOfRow = firstTile;
        Tile movingTile = firstTile;

        do { // Loop column
            do { // Loop row

                // get the next tile right of this one
                TileIdentifier rightIdentifier = movingTile.getTileIdentifier().getRightNeighbour();

                if (rightIdentifier == null
                        || rightIdentifier.getX()
                                > limits.getMaxcol()) { // no more tiles to the right
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "No tiles on the right of " + movingTile.getId());
                    }

                    break;
                }
                Tile rightNeighbour = obtainTile(rightIdentifier);

                // Check if the new tile is still part of the extent
                // TODO! This check doesn't make sense. The tiles are rectangular according to
                // reqExtentInTileCrs, and everyone will intersect.
                // The check should've been against requestedExtent, especially if that one is in a
                // different crs.
                // Then we might end up with tiles that doesn't really fit into the requestedExtent.
                // But then we should also have a transformation.
                boolean intersects =
                        reqExtentInTileCrs.intersects((Envelope) rightNeighbour.getExtent());
                if (intersects) {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(Level.FINE, "Adding right neighbour " + rightNeighbour.getId());
                    }

                    tileList.add(rightNeighbour);

                    movingTile = rightNeighbour;
                } else {
                    if (LOGGER.isLoggable(Level.FINE)) {
                        LOGGER.log(
                                Level.FINE,
                                "Right neighbour out of extents " + rightNeighbour.getId());
                    }

                    break;
                }
                if (tileList.size() > maxNumberOfTiles) {
                    LOGGER.warning(
                            "Reached tile limit of "
                                    + maxNumberOfTiles
                                    + ". Returning the tiles collected so far.");
                    return tileList;
                }
            } while (tileList.size() < maxNumberOfTilesForZoomLevel);

            // get the next tile under the first one of the row

            TileIdentifier lowerIdentifier = firstTileOfRow.getTileIdentifier().getLowerNeighbour();
            if (lowerIdentifier == null
                    || lowerIdentifier.getY() > limits.getMaxrow()) { // no more tiles below
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "No more tiles below " + firstTileOfRow.getId());
                }

                break;
            }

            Tile lowerNeighbour = obtainTile(lowerIdentifier);
            // Check if the new tile is still part of the extent
            // TODO Same as above. Should've been a check against requestExtent
            // It should also take measure for that the left side could be scewed according to
            // reqExtentInTileCrs
            boolean intersects =
                    reqExtentInTileCrs.intersects((Envelope) lowerNeighbour.getExtent());

            if (intersects) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Adding lower neighbour " + lowerNeighbour.getId());
                }

                tileList.add(lowerNeighbour);

                firstTileOfRow = movingTile = lowerNeighbour;
            } else {
                if (LOGGER.isLoggable(Level.FINE))
                    LOGGER.log(
                            Level.FINE, "Lower neighbour out of extents" + lowerNeighbour.getId());
                break;
            }
        } while (tileList.size() < maxNumberOfTilesForZoomLevel);

        return tileList;
    }

    private TileIdentifier identifyUpperLeftTile(double lon, double lat, WMTSZoomLevel zoomLevel) {
        // get the tile in the tilematrix
        TileIdentifier coordTile = identifyTileAtCoordinate(lon, lat, zoomLevel);
        return constrainToUpperLeftTile(coordTile, zoomLevel);
    }

    TileIdentifier constrainToUpperLeftTile(TileIdentifier coordTile, WMTSZoomLevel zl) {

        TileMatrixLimits limits = getLimits(getMatrixSetLink(), getMatrixSet(), zl.getZoomLevel());

        long origxTile = coordTile.getX();
        long origyTile = coordTile.getY();
        long xTile = origxTile;
        long yTile = origyTile;

        if (xTile >= limits.getMaxcol()) xTile = limits.getMaxcol() - 1;
        if (yTile >= limits.getMaxrow()) yTile = limits.getMaxrow() - 1;

        if (xTile < limits.getMincol()) xTile = limits.getMincol();
        if (yTile < limits.getMinrow()) yTile = limits.getMinrow();

        if (origxTile != xTile || origyTile != yTile) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.fine(
                        "findUpperLeftTile: constraining tile within limits: ("
                                + origxTile
                                + ","
                                + origyTile
                                + ") -> ("
                                + xTile
                                + ","
                                + yTile
                                + ")");
            }
        }

        return new WMTSTileIdentifier((int) xTile, (int) yTile, zl, getName());
    }

    private TileMatrixLimits getLimits(TileMatrixSetLink tmsl, TileMatrixSet tms, int z) {

        List<TileMatrixLimits> limitsList = tmsl.getLimits();
        TileMatrixLimits limits;

        if (limitsList != null && z < limitsList.size()) {
            limits = limitsList.get(z);
        } else {
            // no limits defined in layer; let's take all the defined tiles
            TileMatrix tileMatrix = tms.getMatrices().get(z);

            limits = new TileMatrixLimits();
            limits.setMinCol(0L);
            limits.setMinRow(0L);
            limits.setMaxCol(tileMatrix.getMatrixWidth() - 1);
            limits.setMaxRow(tileMatrix.getMatrixHeight() - 1);
            limits.setTileMatix(tms.getIdentifier());
        }

        return limits;
    }

    @Override
    public double[] getScaleList() {
        return scaleList;
    }

    @Override
    public ReferencedEnvelope getBounds() {
        return envelope;
    }

    @Override
    public CoordinateReferenceSystem getProjectedTileCrs() {
        return matrixSet.getCoordinateReferenceSystem();
    }

    @Override
    public TileFactory getTileFactory() {
        return tileFactory;
    }

    public TileMatrixSetLink getMatrixSetLink() {
        return layer.getTileMatrixLinks().get(tileMatrixSetName);
    }

    TileURLBuilder getURLBuilder() {
        return urlBuilder;
    }

    /** */
    public TileMatrix getTileMatrix(int zoomLevel) {
        if (matrixSet == null) {
            throw new RuntimeException("TileMatrix is not set in WMTSService");
        }
        return matrixSet.getMatrices().get(zoomLevel);
    }

    /** @return the matrixSet */
    public TileMatrixSet getMatrixSet() {
        return matrixSet;
    }

    /** @param matrixSet the matrixSet to set */
    public void setMatrixSet(TileMatrixSet matrixSet) {
        this.matrixSet = matrixSet;
        scaleList = new double[matrixSet.size()];
        int j = 0;
        for (TileMatrix tm : matrixSet.getMatrices()) {
            scaleList[j++] = tm.getDenominator();
        }
    }

    public WMTSZoomLevel getZoomLevel(int zoom) {
        return new WMTSZoomLevel(zoom, this);
    }

    /**
     * A place to put any Header that should be sent in http calls.
     *
     * @return
     */
    public Map<String, Object> getExtrainfo() {
        return extrainfo;
    }

    private ScaleZoomLevelMatcher getZoomLevelMatcher(
            ReferencedEnvelope requestExtent, CoordinateReferenceSystem crsTiles, double scale)
            throws FactoryException, TransformException {

        CoordinateReferenceSystem crsMap = requestExtent.getCoordinateReferenceSystem();

        // Transformation: MapCrs -> TileCrs
        MathTransform transformMapToTile = CRS.findMathTransform(crsMap, crsTiles);

        // Transformation: TileCrs -> MapCrs (needed for the blank tiles)
        MathTransform transformTileToMap = CRS.findMathTransform(crsTiles, crsMap);

        // Get the mapExtent in the tiles CRS
        ReferencedEnvelope mapExtentTileCrs =
                getProjectedEnvelope(requestExtent, crsTiles, transformMapToTile);

        return new ScaleZoomLevelMatcher(
                crsMap,
                crsTiles,
                transformMapToTile,
                transformTileToMap,
                mapExtentTileCrs,
                requestExtent,
                scale) {
            @Override
            public int getZoomLevelFromScale(TileService service, double[] tempScaleList) {
                double min = Double.MAX_VALUE;
                double scaleFactor = getScale();
                int zoomLevel = 0;
                for (int i = scaleList.length - 1; i >= 0; i--) {
                    final double v = scaleList[i];
                    final double diff = Math.abs(v - scaleFactor);
                    if (diff < min) {
                        min = diff;
                        zoomLevel = i;
                    }
                }
                return zoomLevel;
            }
        };
    }

    @Override
    public TileIdentifier identifyTileAtCoordinate(double lon, double lat, ZoomLevel zoomLevel) {

        WMTSZoomLevel zl = (WMTSZoomLevel) zoomLevel;
        TileMatrix tileMatrix = getMatrixSet().getMatrices().get(zl.getZoomLevel());

        double pixelSpan = WMTSTileFactory.getPixelSpan(tileMatrix);

        double tileSpanY = (tileMatrix.getTileHeight() * pixelSpan);
        double tileSpanX = (tileMatrix.getTileWidth() * pixelSpan);
        double tileMatrixMinX;
        double tileMatrixMaxY;
        if (tileMatrix
                .getCrs()
                .getCoordinateSystem()
                .getAxis(0)
                .getDirection()
                .equals(AxisDirection.EAST)) {
            tileMatrixMinX = tileMatrix.getTopLeft().getX();
            tileMatrixMaxY = tileMatrix.getTopLeft().getY();
        } else {
            tileMatrixMaxY = tileMatrix.getTopLeft().getX();
            tileMatrixMinX = tileMatrix.getTopLeft().getY();
        }
        // to compensate for floating point computation inaccuracies
        double epsilon = 1e-6;
        long xTile = (int) Math.floor((lon - tileMatrixMinX) / tileSpanX + epsilon);
        long yTile = (int) Math.floor((tileMatrixMaxY - lat) / tileSpanY + epsilon);

        // sanitize
        xTile = Math.max(0, xTile);
        yTile = Math.max(0, yTile);

        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(
                    "identifyTile: (lon,lat)=("
                            + lon
                            + ","
                            + lat
                            + ")  (col,row)="
                            + xTile
                            + ", "
                            + yTile
                            + " zoom:"
                            + zoomLevel.getZoomLevel());
        }

        return new WMTSTileIdentifier((int) xTile, (int) yTile, zoomLevel, getName());
    }
}
