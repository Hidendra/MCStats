package org.mcstats.processing;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import org.apache.log4j.Logger;
import org.mcstats.AccumulatorDelegator;
import org.mcstats.MCStats;
import org.mcstats.accumulator.CustomDataAccumulator;
import org.mcstats.accumulator.MCStatsInfoAccumulator;
import org.mcstats.accumulator.ServerInfoAccumulator;
import org.mcstats.accumulator.VersionInfoAccumulator;
import org.mcstats.db.ModelCache;
import org.mcstats.db.RedisCache;
import org.mcstats.decoder.DecodedRequest;
import org.mcstats.model.Plugin;
import org.mcstats.model.Server;
import org.mcstats.model.ServerPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;

import javax.inject.Singleton;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

@Singleton
public class BatchPluginRequestProcessor {

    private static final Logger logger = Logger.getLogger(BatchPluginRequestProcessor.class);

    /**
     * The maximum amount of allowable version switches in a graph interval before they are blacklisted;
     */
    private static final int MAX_VIOLATIONS_ALLOWED = 7;

    public static final int NUM_THREADS = 16;

    /**
     * The pool used to service requests
     */
    private final ExecutorService servicePool = Executors.newFixedThreadPool(NUM_THREADS, new ThreadFactoryBuilder().setNameFormat(BatchPluginRequestProcessor.class.getSimpleName() + "-%d").build());

    /**
     * The queue used for requests
     */
    private final Queue<DecodedRequest> queue = new LinkedBlockingQueue<>();

    /**
     * SHA of the redis sum add script. TODO better way of storing the SHAs rather than locally?
     */
    private final String redisAddSumScriptSha;

    /**
     * Flag for if the processor is running or not.
     */
    private boolean running = true;

    private final MCStats mcstats;
    private final RedisCache modelCache;
    private final JedisPool redisPool;
    private final AccumulatorDelegator accumulatorDelegator;

    @Inject
    public BatchPluginRequestProcessor(MCStats mcstats, ModelCache modelCache, JedisPool redisPool, AccumulatorDelegator accumulatorDelegator) {
        this.mcstats = mcstats;
        this.modelCache = (RedisCache) modelCache; // TODO inject directly?
        this.redisPool = redisPool;
        this.accumulatorDelegator = accumulatorDelegator;
        this.redisAddSumScriptSha = mcstats.loadRedisScript("/scripts/redis/zadd-sum.lua");

        // TODO add from somewhere else
        accumulatorDelegator.add(new ServerInfoAccumulator(mcstats));
        accumulatorDelegator.add(new MCStatsInfoAccumulator());
        accumulatorDelegator.add(new VersionInfoAccumulator());
        accumulatorDelegator.add(new CustomDataAccumulator());

        for (int i = 0; i < NUM_THREADS; i ++) {
            servicePool.execute(new Worker());
        }
    }

    /**
     * Submits a request to the processor
     *
     * @param request
     */
    public void submit(DecodedRequest request) {
        queue.add(request);
    }

    /**
     * Gets the number of requests waiting to be processed.
     *
     * @return
     */
    public int size() {
        return queue.size();
    }

    /**
     * Shuts down the processor
     */
    public void shutdown() {
        running = false;
        servicePool.shutdown();
    }

    private final class Worker implements Runnable {
        @Override
        public void run() {
            while (running) {
                try (Jedis redis = redisPool.getResource()) {
                    Pipeline pipeline = redis.pipelined();

                    // max to process at one time
                    int remaining = 1000;
                    int processed = 0;

                    while (!queue.isEmpty() && --remaining >= 0) {
                        DecodedRequest request = queue.poll();

                        Server server = loadAndNormalizeServer(request);
                        ServerPlugin serverPlugin = loadServerPlugin(server, request.plugin, request);

                        server.setLastSentData((int) (System.currentTimeMillis() / 1000L));

                        {
                            modelCache.cacheServer(server, pipeline);
                            modelCache.cacheServerPlugin(serverPlugin, pipeline);

                            // accumulate all graph data
                            // TODO break out to somewhere else?
                            Map<Plugin, Map<String, Map<String, Long>>> accumulatedData = accumulatorDelegator.accumulate(request, serverPlugin);

                            accumulatedData.forEach((plugin, data) -> data.forEach((graphName, graphData) -> {
                                graphData.forEach((columnName, value) -> {
                                    String redisDataKey = "plugin-data:" + plugin.getId() + ":" + graphName + ":" + columnName;
                                    String redisDataSumKey = "plugin-data-sum:" + plugin.getId() + ":" + graphName + ":" + columnName;

                                    // metadata
                                    pipeline.sadd("graphs:" + plugin.getId(), graphName);
                                    pipeline.sadd("columns:" + plugin.getId() + ":" + graphName, columnName);

                                    // data
                                    pipeline.evalsha(redisAddSumScriptSha, 2, redisDataKey, redisDataSumKey, Long.toString(value), server.getUUID());
                                });
                            }));
                        }

                        processed ++;
                    }

                    if (processed > 0) {
                        logger.debug("Processed " + processed + " requests");
                    }

                    pipeline.sync();
                    Thread.sleep(5L);
                } catch (InterruptedException e) {
                    logger.debug("Interrupted!", e);
                    break;
                }

            }
        }
    }

    /**
     * Loads and normalizes a server
     *
     * @param decoded
     * @return
     */
    private Server loadAndNormalizeServer(DecodedRequest decoded) {
        Server server = modelCache.getServer(decoded.uuid);

        if (server == null) {
            server = new Server(decoded.uuid);
        }

        if (!server.getCountry().equals(decoded.country)) {
            server.setCountry(decoded.country);
        }

        if (!server.getServerVersion().equals(decoded.serverVersion)) {
            server.setServerVersion(decoded.serverVersion);
        }

        if ((server.getPlayers() != decoded.playersOnline) && (decoded.playersOnline >= 0)) {
            server.setPlayers(decoded.playersOnline);
        }

        String canonicalServerVersion = mcstats.getServerBuildIdentifier().getServerVersion(decoded.serverVersion);
        String minecraftVersion = mcstats.getServerBuildIdentifier().getMinecraftVersion(decoded.serverVersion);

        if (!server.getServerSoftware().equals(canonicalServerVersion)) {
            server.setServerSoftware(canonicalServerVersion);
        }

        if (!server.getMinecraftVersion().equals(minecraftVersion)) {
            server.setMinecraftVersion(minecraftVersion);
        }

        if (decoded.revision >= 6) {
            if (!decoded.osname.equals(server.getOSName())) {
                server.setOSName(decoded.osname);
            }

            if ((decoded.osarch != null) && (!decoded.osarch.equals(server.getOSArch()))) {
                server.setOSArch(decoded.osarch);
            }

            if (!decoded.osversion.equals(server.getOSVersion())) {
                server.setOSVersion(decoded.osversion);
            }

            if (server.getCores() != decoded.cores) {
                server.setCores(decoded.cores);
            }

            if (server.getOnlineMode() != decoded.authMode) {
                server.setOnlineMode(decoded.authMode);
            }

            if (!decoded.javaName.equals(server.getJavaName())) {
                server.setJavaName(decoded.javaName);
            }

            if (!decoded.javaVersion.equals(server.getJavaVersion())) {
                server.setJavaVersion(decoded.javaVersion);
            }
        }

        return server;
    }

    /**
     * Loads and normalizes a server plugin
     *
     * @param server
     * @param plugin
     * @param decoded
     * @return
     */
    private ServerPlugin loadServerPlugin(Server server, Plugin plugin, DecodedRequest decoded) {
        ServerPlugin serverPlugin = modelCache.getServerPlugin(server, plugin);

        if (serverPlugin == null) {
            serverPlugin = new ServerPlugin(server, plugin);
        }

        boolean isBlacklisted = server.getViolationCount() >= MAX_VIOLATIONS_ALLOWED;

        if (!serverPlugin.getVersion().equals(decoded.pluginVersion) && !isBlacklisted) {
            serverPlugin.addVersionChange(serverPlugin.getVersion(), decoded.pluginVersion);
            serverPlugin.setVersion(decoded.pluginVersion);
            server.incrementViolations();
        }

        if (serverPlugin.getRevision() != decoded.revision) {
            serverPlugin.setRevision(decoded.revision);
        }

        if (decoded.revision >= 4 && !server.getCountry().equals("SG")) {
            serverPlugin.setCustomData(decoded.customData);
        }

        return serverPlugin;
    }

}