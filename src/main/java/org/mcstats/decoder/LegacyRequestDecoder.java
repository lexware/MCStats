package org.mcstats.decoder;

import org.eclipse.jetty.server.Request;
import org.mcstats.MCStats;
import org.mcstats.model.Column;
import org.mcstats.model.Graph;
import org.mcstats.model.Plugin;
import org.mcstats.util.URLUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class LegacyRequestDecoder implements RequestDecoder {

    private MCStats mcstats;

    public LegacyRequestDecoder(MCStats mcstats) {
        this.mcstats = mcstats;
    }

    /**
     * {@inheritDoc}
     */
    public DecodedRequest decode(Plugin plugin, Request request) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(request.getInputStream(), "UTF-8"));
        String content = "";

        String line;
        while ((line = reader.readLine()) != null) {
            content += line;
        }
        reader.close();

        Map<String, String> post = processPostRequest(content);

        if (!post.containsKey("guid")) {
            return null;
        }

        DecodedRequest decoded = new DecodedRequest();
        decoded.guid = (String) post.get("guid");
        decoded.serverVersion = (String) post.get("server");
        decoded.pluginVersion = (String) post.get("version");
        decoded.isPing = post.containsKey("ping");

        try {
            decoded.revision = post.containsKey("revision") ? Integer.parseInt((String) post.get("revision")) : 4;
            decoded.playersOnline = post.containsKey("players") ? Integer.parseInt((String) post.get("players")) : 0;
        } catch (NumberFormatException e) {
            return null;
        }

        if (decoded.guid == null || decoded.serverVersion == null || decoded.pluginVersion == null) {
            return null;
        }

        if (decoded.playersOnline < 0 || decoded.playersOnline > 2000) {
            decoded.playersOnline = 0;
        }

        if (decoded.revision >= 6) {
            decoded.osname = (String) post.get("osname");
            decoded.osarch = (String) post.get("osarch");
            decoded.osversion = (String) post.get("osversion");
            decoded.javaName = "";
            decoded.javaVersion = (String) post.get("java_version");
            if (decoded.osname == null) {
                decoded.osname = "Unknown";
                decoded.osversion = "Unknown";
            }
            if (decoded.osversion == null) {
                decoded.osversion = "Unknown";
            }
            if (decoded.javaVersion == null) {
                decoded.javaVersion = "Unknown";
            } else if (decoded.javaVersion.startsWith("1.") && decoded.javaVersion.length() > 3) {
                decoded.javaName = decoded.javaVersion.substring(0, decoded.javaVersion.indexOf('.', decoded.javaVersion.indexOf('.') + 1));
                decoded.javaVersion = decoded.javaVersion.substring(decoded.javaName.length() + 1);
            }
            if (decoded.osname != null) {
                try {
                    decoded.cores = Integer.parseInt((String) post.get("cores"));
                    decoded.authMode = Boolean.parseBoolean((String) post.get("online-mode")) ? 1 : 0;
                } catch (Exception e) {
                    decoded.cores = 0;
                    decoded.authMode = -1;
                }
            }
        }

        if (decoded.revision >= 5) {
            decoded.customData = extractCustomData(plugin, post);
        } else {
            decoded.customData = extractCustomDataLegacy(plugin, post);
        }

        return decoded;
    }

    private Map<String, String> processPostRequest(String content) {
        Map store = new HashMap();
        String arr[] = content.split("&");

        for (int i = 0; i < arr.length; i++) {
            String entry = arr[i];
            String data[] = entry.split("=");
            if (data.length == 2) {
                String key = URLUtils.decode(data[0]);
                String value = URLUtils.decode(data[1]);
                store.put(key, value);
            }
        }

        return store;
    }

    /**
     * Extract the custom data from the post request
     *
     * @param plugin
     * @param post
     * @return
     */
    private Map<Column, Long> extractCustomData(Plugin plugin, Map<String, String> post) {
        Map<Column, Long> customData = new HashMap<Column, Long>();

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = (String) entry.getKey();
            String postValue = (String) entry.getValue();

            if (!postKey.startsWith("C")) {
                continue;
            }

            long value;
            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            String graphData[] = postKey.split("~~");

            if (graphData.length == 3) {
                String graphName = graphData[1];
                String columnName = graphData[2];
                Graph graph = mcstats.loadGraph(plugin, graphName);
                if (graph != null && graph.getActive() != 0) {
                    org.mcstats.model.Column column = graph.loadColumn(columnName);
                    if (column != null) {
                        customData.put(column, Long.valueOf(value));
                    }
                }
            }
        }

        return customData;
    }

    /**
     * Extract legacy custom data (no custom graphs just one graph)
     *
     * @param plugin
     * @param post
     * @return
     */
    private Map<Column, Long> extractCustomDataLegacy(Plugin plugin, Map<String, String> post) {
        Map<Column, Long> customData = new HashMap<Column, Long>();
        Graph graph = mcstats.loadGraph(plugin, "Default");

        for (Map.Entry<String, String> entry : post.entrySet()) {
            String postKey = (String) entry.getKey();
            String postValue = (String) entry.getValue();

            if (!postKey.startsWith("C")) {
                continue;
            }

            long value;
            try {
                value = Integer.parseInt(postValue);
            } catch (NumberFormatException e) {
                continue;
            }

            if (postKey.startsWith("Custom")) {
                String columnName = postKey.substring(6).replaceAll("_", " ");
                if (graph != null) {
                    Column column = graph.loadColumn(columnName);
                    if (column != null) {
                        customData.put(column, Long.valueOf(value));
                    }
                }
            }
        }

        return customData;
    }

}
