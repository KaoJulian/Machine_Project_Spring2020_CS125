package edu.illinois.cs.cs125.spring2020.mp.logic;

import android.content.Context;

import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.neovisionaries.ws.client.WebSocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.illinois.cs.cs125.spring2020.mp.R;

/**
 * Represents a target mode game. Keeps track of target claims and players' paths between targets they captured.
 */
public final class TargetGame extends Game {

    /** The game's proximity threshold in meters. */
    private int proximityThreshold;

    /** Stores Target instances looked up by server ID. */
    private Map<String, Target> targets = new HashMap<>();

    /** Map of player emails to their paths (visited target IDs). */
    private Map<String, List<String>> playerPaths = new HashMap<>();

    /** Score of each team.*/
    private int[] score = new int[TeamID.MAX_TEAM];

    /** Counter for getTeamScore. */
    private int count = 0;

    /** To substitute magic number. */
    private final int mn = 3;

    /**
     * Creates a game in target mode.
     * <p>
     * Loads the existing game state from the JSON provided by the server into instance variables
     * and populates the map accordingly.
     * @param email the player's email
     * @param map the Google Maps control to render to
     * @param webSocket the websocket to send updates to
     * @param fullState the "full" update from the server
     * @param context the Android UI context
     */
    public TargetGame(final String email, final GoogleMap map, final WebSocket webSocket,
               final JsonObject fullState, final Context context) {
        // Call the super constructor so functionality defined in Game will work
        super(email, map, webSocket, fullState, context);

        // Load the proximity threshold from the JSON
        proximityThreshold = fullState.get("proximityThreshold").getAsInt();

        // Load the list of all targets in the game
        for (JsonElement t : fullState.getAsJsonArray("targets")) {
            JsonObject targetInfo = t.getAsJsonObject();

            // Create the Target, which places a marker on the map
            Target target = new Target(map,
                    new LatLng(targetInfo.get("latitude").getAsDouble(), targetInfo.get("longitude").getAsDouble()),
                    targetInfo.get("team").getAsInt());

            // Add it to the targets map so we can look it up by ID later
            targets.put(targetInfo.get("id").getAsString(), target);
        }

        // Load the path of each player, which will be needed for checking for line crosses
        for (JsonElement p : fullState.get("players").getAsJsonArray()) {
            JsonObject player = p.getAsJsonObject();
            String playerEmail = player.get("email").getAsString();

            // Create a list to hold the IDs of targets visited by the player, in order
            List<String> path = new ArrayList<>();
            playerPaths.put(playerEmail, path);

            // Examine each target in the player entry's path
            for (JsonElement t : player.getAsJsonArray("path")) {
                JsonObject target = t.getAsJsonObject();
                String targetId = target.get("id").getAsString();
                extendPlayerPath(playerEmail, targetId, player.get("team").getAsInt());
            }
        }
    }

    /**
     * Called when the user's location changes.
     * <p>
     * Target mode games detect whether the player is within the game's proximity threshold of a target.
     * Capture is possible if the target is unclaimed and the new line segment from the player's previously
     * captured target (if any) does not intersect any other line segment.
     * If a target is captured, a targetVisit update is sent to the server.
     * <p>
     * You need to implement this function, though much of the logic can be organized into
     * the tryClaimTarget helper function below.
     * @param location the player's most recently known location
     */
    @Override
    public void locationUpdated(final LatLng location) {
        super.locationUpdated(location);
        // For each target within range of the player's current location, call tryClaimTarget
        for (Map.Entry<String, Target> entry : targets.entrySet()) {
            if (LatLngUtils.distance(entry.getValue().getPosition(), location) < proximityThreshold) {
                tryClaimTarget(entry.getKey(), entry.getValue());
            }
            // The type names in the angle brackets should match the types in the map
            // The current key is entry.getKey()
            // The current value is entry.getValue()
            // Do something with the key and value?
        }
    }

    /**
     * Processes an update from the server.
     * <p>
     * Since playerTargetVisit events are specific to target mode games, this method handles those.
     * All other events are delegated to the superclass.
     * @param message JSON from the server (the "type" property indicates the update type)
     * @return whether the message type was recognized
     */
    @Override
    public boolean handleMessage(final JsonObject message) {
        // Some messages are common to all games - see if the superclass can handle it
        if (super.handleMessage(message)) {
            // If it took care of the update, this class's implementation doesn't need to do anything
            // Inform the caller that the update was handled
            return true;
        }

        // Check the type of update to see if we can handle it and what to do
        if (message.get("type").getAsString().equals("playerTargetVisit")) {
            // Got an update indicating that another player captured a target
            // Load the information from the JSON
            String playerEmail = message.get("email").getAsString();
            String targetId = message.get("targetId").getAsString();
            int playerTeam = message.get("team").getAsInt();

            // You need to use that information to update the game state and map
            // First update the captured target's team
            targets.get(targetId).setTeam(playerTeam);
            score[playerTeam - 1]++;
            // Then call a helper function to update the player's path and add any needed line to the map
            List path = playerPaths.get(playerEmail);
            path.add(targetId);
            if (path.size() > 1) {
                addLineSegment(targets.get(path.get(path.size() - 2)).getPosition(),
                        targets.get(path.get(path.size() - 1)).getPosition(), playerTeam);
            }

            // Once that's done, inform the caller that we handled it
            return true;
        } else {
            // An unknown type of update was received - inform the caller of the situation
            return false;
        }
    }

    /**
     * Claims a target if possible.
     * <p>
     * You need to implement this helper function to help locationUpdated do its job.
     * @param id the server ID of the target
     * @param target the target
     */
    private void tryClaimTarget(final String id, final Target target) {
        // Make sure the target isn't already captured - return if it's already taken
        if (target.getTeam() != TeamID.OBSERVER) {
            return;
        } else {
            for (Map.Entry<String, List<String>> entry : playerPaths.entrySet()) {
                if (entry.getKey().equals(getEmail())) {
                    List<String> path = entry.getValue();

                    // See if the player has already captured a target - if yes:
                    if (path.size() != 0) {
                        LatLng last = targets.get(path.get(path.size() - 1)).getPosition();
                        LatLng current = target.getPosition();
                        // See if the line between this target and the player's last capture
                        // intersects any existing line
                        // (make sure to check for crossing with all players' paths)
                        // If lines would cross, return
                        for (Map.Entry<String, List<String>> each : playerPaths.entrySet()) {
                            List<String> otherPath = each.getValue();
                            if (otherPath != path) {
                                if (otherPath.size() >= 2) {
                                    for (int i = 0; i < otherPath.size() - 1; i++) {
                                        LatLng first = targets.get(otherPath.get(i)).getPosition();
                                        LatLng second = targets.get(otherPath.get(i + 1)).getPosition();
                                        if (LineCrossDetector.linesCross(first, second, last, current)) {
                                            return;
                                        }
                                    }
                                }
                            }
                            if (otherPath == path) {
                                if (path.size() > mn) {
                                    for (int i = 0; i < path.size() - mn; i++) {
                                        LatLng first = targets.get(path.get(i)).getPosition();
                                        LatLng second = targets.get(path.get(i + 1)).getPosition();
                                        if (LineCrossDetector.linesCross(first, second, last, current)) {
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // Now that we know the target can be captured, update its owning team
            target.setTeam(getMyTeam());
            score[getMyTeam() - 1]++;
            // Use extendPlayerPath to update the game state and map
            extendPlayerPath(getEmail(), id, getMyTeam());
            // Send a targetVisit update to the server
            JsonObject message = new JsonObject();
            message.addProperty("type", "targetVisit");
            message.addProperty("targetId", id);
            sendMessage(message);
        }
    }

    /**
     * Adds a target to a player's path.
     * <p>
     * Updates the game state (the player's path list in playerPaths) and places a line on
     * the map (if appropriate) to display the capture.
     * <p>
     * You do not need to modify this function, but you will need to make the addLineSegment
     * helper function that it depends on work.
     * @param email email of the player who just visited the target
     * @param targetId ID of the target
     * @param team the player's team ID
     */
    private void extendPlayerPath(final String email, final String targetId, final int team) {
        // Get the specified player's path from the players/paths map
        List<String> path = playerPaths.get(email);

        // If this player has visited a target before, their path will be non-empty
        if (!path.isEmpty()) {
            // Get the positions of the previously and currently visited targets from the targets map
            LatLng lastTargetPos = targets.get(path.get(path.size() - 1)).getPosition();
            LatLng currentTargetPos = targets.get(targetId).getPosition();

            // Use a helper function to draw the line
            addLineSegment(lastTargetPos, currentTargetPos, team);
        }

        // Add this newly captured target to their path
        path.add(targetId);
    }

    /**
     * Adds a line segment to the map to indicate part of a player's path.
     * <p>
     * You need to implement this helper function so that extendPlayerPath can update the map.
     * @param start one endpoint
     * @param end the other endpoint
     * @param team a team ID (not OBSERVER)
     */
    private void addLineSegment(final LatLng start, final LatLng end, final int team) {
        // Place a line (Polyline) on the Google map, colored as appropriate for the team
        // See the provided addLine function from the old GameActivity for an example of how to add lines
        // The colors to use are provided by the team_colors integer array resource
        // (that's why Game instances need an Android Context object)
        // You may add the extra black border line if you like
        final int lineThickness = 12;
        int[] colors = getContext().getResources().getIntArray(R.array.team_colors);
        PolylineOptions fill = new PolylineOptions().add(start, end).color(colors[team]).width(lineThickness).zIndex(1);
        getMap().addPolyline(fill);
    }

    /**
     * Gets a team's score in this target mode game.
     * <p>
     * You need to implement this function.
     * @param teamId the team ID (same kind of value as the TeamID constants)
     * @return the number of targets owned by the team
     */
    @Override
    public int getTeamScore(final int teamId) {
        if (count == 0) {
            for (Map.Entry<String, Target> entry : targets.entrySet()) {
                if (entry.getValue().getTeam() != 0) {
                    score[entry.getValue().getTeam() - 1]++;
                }
            }
            count++;
        }

        // Find how many targets are currently owned by the specified team
        return score[teamId - 1];
    }

    /**
     * For getWinningTeam function in Game class.
     * @return The winning team.
     */
    public int gWT() {
        int result = score[0];
        for (int i = 0; i < score.length; i++) {
            if (score[i] > result) {
                result = score[i];
            }
        }
        for (int i = 0; i < score.length; i++) {
            if (score[i] == result) {
                return i + 1;
            }
        }
        return 0;
    }
}
