package com.openclassrooms.tourguide.service;

import java.util.ArrayList;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;

	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;

	// threadPool
	private static final int THREAD_POOL_SIZE = Math.max(32, Runtime.getRuntime().availableProcessors() * 2);
	private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

	// cache to store calculated distances
	private final ConcurrentHashMap<String, Double> distanceCache = new ConcurrentHashMap<>();

	// cache to store rewardPoints already computed
	private final ConcurrentHashMap<String, Integer> rewardPointsCache = new ConcurrentHashMap<>();

	public double getCachedDistance(Location loc1, Location loc2) {
		String key = String.format("%.4f,%.4f_%.4f,%.4f", loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude);
		return distanceCache.computeIfAbsent(key, k -> getDistance(loc1, loc2));
	}

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
	}
	
	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}
	
	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	public void calculateRewards(User user) {
		List<VisitedLocation> userLocations = new ArrayList<>(user.getVisitedLocations());
		List<Attraction> attractions = gpsUtil.getAttractions();
		List<Future<?>> futures = new ArrayList<>();

		for (VisitedLocation visitedLocation : userLocations) {

			List<Attraction> nearbyAttractions = attractions.stream()
					.filter(attraction -> getCachedDistance(attraction, visitedLocation.location) <= proximityBuffer)
					.toList();

			for (Attraction attraction : nearbyAttractions) {

				Future<?> future = executor.submit(() -> {
					if (user.getUserRewards().stream()
							.noneMatch(r -> r.attraction.attractionName.equals(attraction.attractionName))) {

						int rewardPoints = getRewardPoints(attraction, user);
						synchronized (user) {
							user.addUserReward(new UserReward(visitedLocation, attraction, rewardPoints));
						}
					}
				});

				futures.add(future);
			}
		}

		for (Future<?> future : futures) {
			try {
				future.get();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) > attractionProximityRange ? false : true;
	}
	
	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}
	
	public int getRewardPoints(Attraction attraction, User user) {
		String key = attraction.attractionId + "_" + user.getUserId();
		return rewardPointsCache.computeIfAbsent(key, k -> rewardsCentral.getAttractionRewardPoints(attraction.attractionId, user.getUserId()));
	}
	
	public double getDistance(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude);
        double lon1 = Math.toRadians(loc1.longitude);
        double lat2 = Math.toRadians(loc2.latitude);
        double lon2 = Math.toRadians(loc2.longitude);

        double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
                               + Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

        double nauticalMiles = 60 * Math.toDegrees(angle);
        double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
        return statuteMiles;
	}

}
