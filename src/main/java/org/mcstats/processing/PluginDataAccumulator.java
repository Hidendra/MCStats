package org.mcstats.processing;

import com.google.gson.Gson;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Logger;
import org.mcstats.PluginAccumulator;
import org.mcstats.decoder.DecodedRequest;
import org.mcstats.guice.GuiceModule;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class PluginDataAccumulator implements Runnable {

    private static final Logger logger = Logger.getLogger(PluginDataAccumulator.class);

    private final Gson gson;
    private final JedisPool redisPool;
    private final PluginAccumulator accumulator;

    @Inject
    public PluginDataAccumulator(Gson gson, JedisPool redisPool, PluginAccumulator accumulator) {
        this.gson = gson;
        this.redisPool = redisPool;
        this.accumulator = accumulator;
    }

    @Override
    public void run() {
        // TODO calculate the bucket id
        // redis set of unaggregated buckets?
        // That *should* allow aggregation of past buckets if needed (e.g. aggregator stops for a couple hours).
        final int bucket = 1432308600;

        final Set<String> pluginIds = getPlugins(bucket);

        long start = System.currentTimeMillis();

        // cauldron for global stats (i.e. all servers)
        final GraphCauldron globalCauldron = new GraphCauldron();

        // TODO this should be safely parallelized
        pluginIds.parallelStream().mapToInt(Integer::parseInt).forEach(pluginId -> {
            try (Jedis redis = redisPool.getResource()) {
                logger.debug("Accumulating data for plugin: " + pluginId);

                final GraphCauldron pluginCauldron = new GraphCauldron();

                // server data
                final Map<String, String> serverData = redis.hgetAll("plugin-data:" + bucket + ":" + pluginId);

                // versions for all servers
                final Map<String, Set<String>> serverVersions = getPluginVersions(redis, bucket, pluginId, serverData.keySet());

                serverData.forEach((serverId, data) -> {
                    DecodedRequest request = gson.fromJson(data, DecodedRequest.class);

                    if (request == null) {
                        return;
                    }

                    // note: getPluginVersions guarantees this is non-null
                    final Set<String> versions = serverVersions.get(serverId);
                    Map<Integer, Map<String, Map<String, Long>>> accumulatedData = accumulator.accumulate(request, versions);

                    accumulatedData.forEach((accumPluginId, accumPluginData) -> accumPluginData.forEach((graphName, graphData) -> {
                        if (accumPluginId == PluginAccumulator.GLOBAL_PLUGIN_ID) {
                            globalCauldron.mix(accumPluginData);
                        } else if (accumPluginId == pluginId) {
                            pluginCauldron.mix(accumPluginData);
                        } else {
                            throw new UnsupportedOperationException("Accumulated data for unexpected plugin: " + accumPluginId + " was expecting: " + pluginId + " or global");
                        }
                    }));
                });

                System.out.println(pluginId + " -> " + gson.toJson(pluginCauldron.getData()));
            }
        });

        System.out.println("global -> " + gson.toJson(globalCauldron.getData()));

        long taken = System.currentTimeMillis() - start;
        logger.debug("Accumulated " + pluginIds.size() + " plugins in " + taken + " ms");
    }

    /**
     * Gets all plugins in the given bucket
     *
     * @param bucket
     * @return
     */
    private Set<String> getPlugins(int bucket) {
        try (Jedis redis = redisPool.getResource()) {
            Set<String> result = redis.smembers("plugins:" + bucket);

            if (result != null) {
                return result;
            } else {
                return new HashSet<>();
            }
        }
    }

    /**
     * Gets plugin versions for every server given a plugin id. Every server is guaranteed to return a non-null Set,
     * whether or not it's empty.
     * <p>
     * This uses a pipeline to mass fetch every version without wasting RTTs.
     *
     * @param redis
     * @param pluginId
     * @param serverIds
     * @return Plugin versions for every server. Each passed serverId is guaranteed to have a non-null Set (empty or not).
     */
    private Map<String, Set<String>> getPluginVersions(Jedis redis, int bucket, int pluginId, Set<String> serverIds) {
        Map<String, Set<String>> result = new HashMap<>();

        Map<String, Response<Set<String>>> versionResponses = new HashMap<>();

        {
            Pipeline pipeline = redis.pipelined();

            for (String serverId : serverIds) {
                final String versionKey = "plugin-versions:" + bucket + ":" + serverId + ":" + pluginId;

                versionResponses.put(serverId, pipeline.smembers(versionKey));
            }

            pipeline.sync();
        }

        versionResponses.forEach((serverId, versionResponse) -> {
            Set<String> versions = versionResponse.get();

            if (versions == null) {
                versions = new HashSet<>();
            }

            result.put(serverId, versions);
        });

        return result;
    }

    public static void main(String[] args) {
        BasicConfigurator.configure();

        Injector injector = Guice.createInjector(new GuiceModule());
        PluginDataAccumulator accumulator = injector.getInstance(PluginDataAccumulator.class);

        accumulator.run();
    }

}
