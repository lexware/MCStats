package org.mcstats.handler;

import org.apache.log4j.Logger;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.mcstats.MCStats;
import org.mcstats.model.Column;
import org.mcstats.model.Graph;
import org.mcstats.model.Plugin;
import org.mcstats.model.PluginVersion;
import org.mcstats.model.RawQuery;
import org.mcstats.model.Server;
import org.mcstats.model.ServerPlugin;
import org.mcstats.util.URLUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class ReportHandler extends AbstractHandler {
    private Logger logger = Logger.getLogger("ReportHandler");

    /**
     * The maximum amount of allowable version switches in a graph interval before they are blacklisted;
     */
    private static final int MAX_VIOLATIONS_ALLOWED = 7;

    /**
     * The MCStats object
     */
    private MCStats mcstats;

    public ReportHandler(MCStats mcstats) {
        this.mcstats = mcstats;
    }

    public void handle(String target, Request request, HttpServletRequest servletRequest, HttpServletResponse response) throws IOException, ServletException {
        // If they aren't posting to us, we don't want to know about them :p
        if (!request.getMethod().equals("POST")) {
            return;
        }

        // Get the plugin name
        String pluginName = getPluginName(request);

        if (pluginName == null) {
            request.setHandled(true);
            response.getWriter().println("ERR Invalid arguments.");
            return;
        }

        // the full request contents
        String content = "";

        // Read the request
        BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream()));
        String line;

        while ((line = reader.readLine()) != null) {
            content += line;
        }

        // System.out.println("content => " + content);

        // Close our reader, no longer needed
        reader.close();

        // Decode the post request
        Map<String, String> post = processPostRequest(content);
        // System.out.println(post);

        // Check for required values
        if (!post.containsKey("guid")) {
            request.setHandled(true);
            response.getWriter().println("ERR Invalid arguments.");
            return;
        }

        // GeoIP
        String geoipCountryCode = request.getHeader("GEOIP_COUNTRY_CODE");

        // Data that was posted to us
        String guid = post.get("guid");
        String serverVersion = post.get("server");
        String pluginVersion = post.get("version");
        boolean isPing = post.containsKey("ping");
        int revision;
        int players;

        // gracefully pull out numbers incase they send malformed numbers
        try {
            revision = post.containsKey("revision") ? Integer.parseInt(post.get("revision")) : 4;
            players = post.containsKey("players") ? Integer.parseInt(post.get("players")) : 0;
        } catch (NumberFormatException e) {
            request.setHandled(true);
            response.getWriter().println("ERR Invalid arguments.");
            e.printStackTrace();
            return;
        }

        // Check for nulls
        if (guid == null || serverVersion == null || pluginVersion == null) {
            request.setHandled(true);
            response.getWriter().println("ERR Invalid arguments.");
            return;
        }

        // Load the plugin
        Plugin plugin = mcstats.loadPlugin(pluginName);
        // logger.info("plugin [ id => " + plugin.getId() + " , name => " + plugin.getName() + " ]");

        // Load the server
        Server server = mcstats.loadServer(guid);
        // logger.info("server [ id => " + server.getId() + " , guid => " + server.getGUID() + " ]");

        // Check violations
        if (server.getViolationCount() < MAX_VIOLATIONS_ALLOWED && mcstats.getDatabase().isServerBlacklisted(server)) {
            server.setViolationCount(MAX_VIOLATIONS_ALLOWED);
            server.setBlacklisted(true);
        }

        if (server.isBlacklisted()) {
            request.setHandled(true);
            response.getWriter().println("ERR Your server is blacklisted for running multiple servers on the same GUID.");
            return;
        }

        if (server.getViolationCount() >= MAX_VIOLATIONS_ALLOWED) {
            server.setBlacklisted(true);
            mcstats.getDatabase().blacklistServer(server);
        }

        // Something bad happened
        if (plugin == null || server == null) {
            request.setHandled(true);
            response.getWriter().println("ERR Something bad happened..");
            return;
        }

        // Load the server plugin object which stores data shared between this server and plugin
        ServerPlugin serverPlugin = mcstats.loadServerPlugin(server, plugin, pluginVersion);

        // logger.info("ServerPlugin => " + serverPlugin.getVersion() + " , " + serverPlugin.getUpdated());

        // Something bad happened????
        if (serverPlugin == null) {
            request.setHandled(true);
            response.getWriter().println("ERR Something bad happened..");
            return;
        }

        // Now check the basic stuff
        if (!serverPlugin.getVersion().equals(pluginVersion)) {
            // only add version history if their current version isn't blank
            // if their current version is blank, that means they just
            // installed the plugin
            if (!server.getServerVersion().isEmpty()) {
                PluginVersion version = mcstats.loadPluginVersion(plugin, pluginVersion);

                if (version != null) {
                    server.addVersionHistory(version);
                }
            }

            serverPlugin.setVersion(pluginVersion);
        }
        if (!server.getServerVersion().equals(serverVersion)) {
            server.setServerVersion(serverVersion);
        }
        if (server.getPlayers() != players && players >= 0) {
            server.setPlayers(players);
        }

        if (geoipCountryCode != null && !geoipCountryCode.isEmpty() && !server.getCountry().equals(geoipCountryCode)) {
            server.setCountry(geoipCountryCode);
        }

        // Increment start counters if needed
        if (!isPing) {
            plugin.setGlobalHits(plugin.getGlobalHits() + 1);
            // remove server startups ?
        }

        // Custom Data
        if (revision >= 4) {
            Map<Column, Integer> customData;

            // Extract custom data
            if (revision >= 5) {
                customData = extractCustomData(plugin, post);
            } else { // legacy
                customData = extractCustomDataLegacy(plugin, post);
            }

            if (customData != null && customData.size() > 0) {
                // Begin building the query
                String query = "INSERT INTO CustomData (Server, Plugin, ColumnID, DataPoint, Updated) VALUES";
                int currentSeconds = (int) (System.currentTimeMillis() / 1000);

                // Iterate through each column
                for (Map.Entry<Column, Integer> entry : customData.entrySet()) {
                    Column column = entry.getKey();
                    int value = entry.getValue();

                    // append the query
                    query += " (" + server.getId() + ", " + plugin.getId() + ", " + column.getId() + ", " + value + ", " + currentSeconds + "),";
                }

                // Remove the last comma
                query = query.substring(0, query.length() - 1);

                // add the duplicate key entry
                query += " ON DUPLICATE KEY UPDATE DataPoint = VALUES(DataPoint) , Updated = VALUES(Updated)";

                // queue the query
                new RawQuery(mcstats, query).save();
            }
        }

        // Begin sending the response
        response.setContentType("text/html;charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);
        request.setHandled(true);

        // get the last graph time
        int lastGraphUpdate = normalizeTime();

        if (lastGraphUpdate > serverPlugin.getUpdated()) {
            server.setViolationCount(0);
            response.getWriter().println("OK This is your first update this hour.");
        } else {
            response.getWriter().println("OK");
        }

        // force the server plugin to update
        serverPlugin.setUpdated((int) (System.currentTimeMillis() / 1000));

        // Save everything
        // They keep flags internally to know if something was modified so all is well
        server.save();
        plugin.save();
        serverPlugin.save();
    }

    /**
     * Normalize the time to the nearest graphing period
     *
     * @return
     */
    private int normalizeTime() {
        int currentTimeSeconds = (int) (System.currentTimeMillis() / 1000);

        // The graphing interval in minutes
        // TODO not hardcoded :3
        int interval = 30;

        // calculate the devisor denominator
        int denom = interval * 60;

        return Math.round((currentTimeSeconds - (denom / 2)) / denom) * denom;
    }

    /**
     * Extract the custom data from a post request
     *
     * @param plugin
     * @param post
     * @return
     */
    private Map<Column, Integer> extractCustomData(Plugin plugin, Map<String, String> post) {
        Map<Column, Integer> customData = new HashMap<Column, Integer>();

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = entry.getKey();
            String postValue = entry.getValue();

            // we only want numeric values, skip the rest
            int value;

            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            // Split by the separator
            // [0] = magic str
            // [1] = graph name
            // [2] = column name
            String[] graphData = postKey.split("~~");

            if (graphData.length != 3) {
                continue;
            }

            // get the data as mentioned above
            String graphName = graphData[1];
            String columnName = graphData[2];

            // Load the graph
            Graph graph = mcstats.loadGraph(plugin, graphName);

            if (graph == null) {
                continue;
            }

            // Load the column
            Column column = graph.loadColumn(columnName);

            if (column != null) {
                customData.put(column, value);
            }
        }

        return customData;
    }

    /**
     * Extract custom data from a Metrics R4 reporter
     *
     * @param plugin
     * @param post
     * @return
     */
    private Map<Column, Integer> extractCustomDataLegacy(Plugin plugin, Map<String, String> post) {
        Map<Column, Integer> customData = new HashMap<Column, Integer>();

        // All of the custom data is thrown onto the 'Default' graph since we have no
        // idea what a "graph" is back in R4
        Graph graph = mcstats.loadGraph(plugin, "Default");

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = entry.getKey();
            String postValue = entry.getValue();

            // we only want numeric values, skip the rest
            int value;

            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            // Look for the magic string
            if (!postKey.startsWith("Custom")) {
                continue;
            }

            // Extract the column name
            String columnName = postKey.substring(6).replaceAll("_", " ");

            if (graph == null) {
                continue;
            }

            // Load the column
            Column column = graph.loadColumn(columnName);

            if (column != null) {
                customData.put(column, value);
            }
        }

        return customData;
    }

    /**
     * Get the plugin name from a request
     *
     * @param request
     * @return
     */
    private String getPluginName(Request request) {
        String url = request.getRequestURI();

        // /report/PluginName
        if (url.startsWith("/report/")) {
            return url.substring("/report/".length());
        }

        // /report.php?plugin=PluginName
        else if (url.startsWith("/report.php?plugin=")) {
            return url.substring("/report.php?plugin=".length());
        }

        return null;
    }

    /**
     * Process a post request and return all of its key/value pairs
     *
     * @param content
     * @return
     */
    private Map<String, String> processPostRequest(String content) {
        Map<String, String> store = new HashMap<String, String>();

        // Split the post data by &
        for (String entry : content.split("&")) {
            // Split it by =
            String[] data = entry.split("=");

            // Check that there is sufficient data in the data array
            if (data.length != 2) {
                continue;
            }

            // decode the data
            String key = URLUtils.decode(data[0]);
            String value = URLUtils.decode(data[1]);

            // Add it to the store
            store.put(key, value);
        }

        return store;
    }

}