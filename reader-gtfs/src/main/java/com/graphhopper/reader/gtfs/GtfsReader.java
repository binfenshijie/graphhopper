package com.graphhopper.reader.gtfs;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.*;
import com.graphhopper.reader.DataReader;
import com.graphhopper.reader.dem.ElevationProvider;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.storage.GraphHopperStorage;
import com.graphhopper.storage.NodeAccess;
import com.graphhopper.util.DistanceCalc;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.Helper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

class GtfsReader implements DataReader {

	private static final Logger LOGGER = LoggerFactory.getLogger(GtfsReader.class);

	private final GraphHopperStorage ghStorage;
	private final GtfsStorage gtfsStorage;
	private File file;
    private long nElementaryConnections;

	private final DistanceCalc distCalc = Helper.DIST_EARTH;

	GtfsReader(GraphHopperStorage ghStorage) {
		this.ghStorage = ghStorage;
		this.ghStorage.create(1000);
		this.gtfsStorage = (GtfsStorage) ghStorage.getExtension();
	}

	@Override
	public DataReader setFile(File file) {
		this.file = file;
		return this;
	}

	@Override
	public DataReader setElevationProvider(ElevationProvider ep) {
		return this;
	}

	@Override
	public DataReader setWorkerThreads(int workerThreads) {
		return this;
	}

	@Override
	public DataReader setEncodingManager(EncodingManager em) {
		return this;
	}

	@Override
	public DataReader setWayPointMaxDistance(double wayPointMaxDistance) {
		return this;
	}

	@Override
	public void readGraph() throws IOException {
		GTFSFeed feed = GTFSFeed.fromFile(file.getPath());
		NodeAccess nodeAccess = ghStorage.getNodeAccess();
		TreeMap<Integer, AbstractPtEdge> edges = new TreeMap<>();
		int i=0;
		int j=0;
		Map<String,Integer> stops = new HashMap<>();
 		for (Stop stop : feed.stops.values()) {
 			stops.put(stop.stop_id, i);
			nodeAccess.setNode(i++, stop.stop_lat, stop.stop_lon);
			ghStorage.edge(i, i);
			edges.put(j, new StopLoopEdge());
			j++;
		}
		LOGGER.info("Created " + i + " nodes from GTFS stops.");
		feed.findPatterns();
        LocalDate startDate = feed.calculateStats().getStartDate();
        LocalDate endDate = feed.calculateStats().getEndDate();
        for (Pattern pattern : feed.patterns.values()) {
			try {
				List<SortedMap<Integer, Integer>> departureTimeXTravelTime = new ArrayList<>();
				for(int y=0; y<pattern.orderedStops.size()-1; y++) {
					SortedMap<Integer, Integer> e = new TreeMap<>();
					departureTimeXTravelTime.add(e);
				}
				String prev = null;
				for (String tripId : pattern.associatedTrips) {
					Trip trip = feed.trips.get(tripId);
					Service service = feed.services.get(trip.service_id);
					Collection<Frequency> frequencies = feed.getFrequencies(tripId);
					Iterable<StopTime> interpolatedStopTimesForTrip = feed.getInterpolatedStopTimesForTrip(tripId);
					int offset = 0;
					for(LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
						if (service.activeOn(date)) {
							if (frequencies.isEmpty()) {
								insert(offset, departureTimeXTravelTime, interpolatedStopTimesForTrip);
							} else {
								for (Frequency frequency : frequencies) {
									for (int time = frequency.start_time; time < frequency.end_time; time += frequency.headway_secs) {
										insert(time - frequency.start_time + offset, departureTimeXTravelTime, interpolatedStopTimesForTrip);
									}
								}
							}
						}
						offset += GtfsHelper.time(24, 0, 0);
					}
				}
				int y=0;
				for (String orderedStop : pattern.orderedStops) {
					if (prev != null) {
						double distance = distCalc.calcDist(
								feed.stops.get(prev).stop_lat,
								feed.stops.get(prev).stop_lon,
								feed.stops.get(orderedStop).stop_lat,
								feed.stops.get(orderedStop).stop_lon);
						EdgeIteratorState edge = ghStorage.edge(
								stops.get(prev),
								stops.get(orderedStop),
								distance,
								false);
						edge.setName(pattern.name);
						edges.put(edge.getEdge(), AbstractPatternHopEdge.createHopEdge(departureTimeXTravelTime.get(y-1)));
						j++;
					}
					prev=orderedStop;
					y++;
				}
			} catch (GTFSFeed.FirstAndLastStopsDoNotHaveTimes e) {
				throw new RuntimeException(e);
			}
		}
		for (Transfer transfer : feed.transfers.values()) {
			if (transfer.transfer_type == 2 && !transfer.from_stop_id.equals(transfer.to_stop_id)) {
				Stop fromStop = feed.stops.get(transfer.from_stop_id);
				Stop toStop = feed.stops.get(transfer.to_stop_id);
				double distance = distCalc.calcDist(
						fromStop.stop_lat,
						fromStop.stop_lon,
						toStop.stop_lat,
						toStop.stop_lon);
				EdgeIteratorState edge = ghStorage.edge(
						stops.get(transfer.from_stop_id),
						stops.get(transfer.to_stop_id),
						distance,
						false);
				edge.setName("Transfer: "+fromStop.stop_name + " -> " + toStop.stop_name);
				edges.put(edge.getEdge(), new GtfsTransferEdge(transfer));
				j++;
			}
		}
		gtfsStorage.setEdges(edges);
		gtfsStorage.setRealEdgesSize(j);
		LOGGER.info("Created " + j + " edges from GTFS trip hops and transfers.");
        LOGGER.info("Created " + nElementaryConnections + " elementary connections.");
    }

	private void insert(int time, List<SortedMap<Integer, Integer>> departureTimeXTravelTime, Iterable<StopTime> stopTimes) throws GTFSFeed.FirstAndLastStopsDoNotHaveTimes {
        StopTime prev = null;
		int i=0;
		for (StopTime orderedStop : stopTimes) {
			if (prev != null) {
                int travelTime = (orderedStop.arrival_time - prev.departure_time);
                int departureTimeFromStartOfSchedule = prev.departure_time + time;
                addElementaryConnection(departureTimeXTravelTime, i, travelTime, departureTimeFromStartOfSchedule);
            }
			prev = orderedStop;
			i++;
		}
	}

    private void addElementaryConnection(List<SortedMap<Integer, Integer>> departureTimeXTravelTime, int i, int travelTime, int departureTimeFromStartOfSchedule) {
        nElementaryConnections++;
        if (nElementaryConnections % 1000000 == 0) {
            LOGGER.info("elementary connection " + nElementaryConnections / 1000000 + "m");
        }
        departureTimeXTravelTime.get(i-1).put(departureTimeFromStartOfSchedule, travelTime);
    }

    @Override
	public Date getDataDate() {
		return null;
	}
}