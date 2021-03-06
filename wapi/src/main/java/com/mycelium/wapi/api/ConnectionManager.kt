package com.mycelium.wapi.api

import com.mycelium.WapiLogger
import com.mycelium.wapi.api.jsonrpc.*
import kotlinx.coroutines.experimental.*
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import kotlin.concurrent.timerTask

class ConnectionManager(private val connectionsCount: Int, private val endpoints: Array<TcpEndpoint>,
                        val logger: WapiLogger) {
    @Volatile
    private var maintenanceTimer: Timer? = null
    @Volatile
    private var isNetworkConnected: Boolean = true
    private val subscriptions = mutableMapOf<String, Subscription>()

    //Currently active clients
    private val jsonRpcTcpClientsList = PriorityBlockingQueue<JsonRpcTcpClient>(connectionsCount,
            Comparator { first, second ->
                (second.lastSuccessTime - first.lastSuccessTime).compareTo(0)
            })

    // Currently reconnecting, or trying to connect clients. Order is: first connected, then not connected.
    private val maintenancedClientsList = PriorityBlockingQueue<JsonRpcTcpClient>(connectionsCount,
            Comparator { first, second ->
                when {
                    !first.isConnected.get() && second.isConnected.get() -> 1
                    first.isConnected.get() && !second.isConnected.get() -> -1
                    else -> 0
                }
            })

    init {
        createConnections(connectionsCount, endpoints, logger)
        activateMaintenanceTimer(connectionsCount, endpoints, logger, MAINTENANCE_INTERVAL, MAINTENANCE_INTERVAL)
    }

    /**
     * Should be called if network connection status has changed
     * @param isConnected - is network connection active.
     */
    fun setNetworkConnected(isConnected: Boolean) {
        logger.logInfo("Connection changed, connected: $isConnected")
        isNetworkConnected = isConnected
        setActive(isConnected)
        jsonRpcTcpClientsList.forEach( JsonRpcTcpClient::renewSubscriptions )
        maintenancedClientsList.forEach( JsonRpcTcpClient::renewSubscriptions )
    }

    fun setActive(isActive: Boolean) {
        logger.logInfo("Connection support changed, new state: $isActive")
        maintenanceTimer?.cancel()
        maintenanceTimer = null
        currentMode = if (isActive) {
            activateMaintenanceTimer(connectionsCount, endpoints, logger, 0L, MAINTENANCE_INTERVAL)
            ConnectionManagerMode.ACTIVE
        } else {
            activateMaintenanceTimer(connectionsCount, endpoints, logger, INACTIVE_MAINTENANCE_INTERVAL, INACTIVE_MAINTENANCE_INTERVAL)
            ConnectionManagerMode.PASSIVE
        }
    }

    fun subscribe(subscription: Subscription) {
        launch {
            val client = getClient()
            client.subscribe(subscription)
            jsonRpcTcpClientsList.put(client)
        }
    }

    @Throws(CancellationException::class)
    fun write(methodName: String, params: RpcParams): RpcResponse {
        if (!isNetworkConnected) {
            throw CancellationException("No network connection")
        }
        return runBlocking {
            withTimeout(MAX_WRITE_TIME) {
                var response: RpcResponse? = null
                while (response == null) {
                    val client = getClient()
                    try {
                        response = client.write(methodName, params)
                        jsonRpcTcpClientsList.put(client)
                    } catch (e: TimeoutException) {
                        maintenancedClientsList.put(client)
                    }
                }
                response!!
            }
        }
    }

    @Throws(CancellationException::class)
    fun write(requests: List<RpcRequestOut>): BatchedRpcResponse {
        if (!isNetworkConnected) {
            throw CancellationException("No network connection")
        }
        return runBlocking {
            withTimeout(MAX_WRITE_TIME) {
                var response: BatchedRpcResponse? = null
                while (response == null) {
                    val client = getClient()
                    try {
                        response = client.write(requests)
                        jsonRpcTcpClientsList.put(client)
                    } catch (e: TimeoutException) {
                        maintenancedClientsList.put(client)
                    }
                }
                response!!
            }
        }
    }

    private fun createConnections(connectionsCount: Int, endpoints: Array<TcpEndpoint>, logger: WapiLogger) {
        for (i in 1..connectionsCount) {
            val client = JsonRpcTcpClient(endpoints, logger)
            jsonRpcTcpClientsList.add(client)
            client.start()
        }
    }

    private fun activateMaintenanceTimer(connectionsCount: Int, endpoints: Array<TcpEndpoint>,
                                         logger: WapiLogger, initialInterval: Long, executionInterval: Long) {
        maintenanceTimer = Timer()
        maintenanceTimer?.scheduleAtFixedRate(timerTask {
            doMaintenance(connectionsCount, endpoints, logger)
        }, initialInterval, executionInterval)
    }

    private fun doMaintenance(connectionsCount: Int, endpoints: Array<TcpEndpoint>, logger: WapiLogger) {
        // If some of clients reconnected
        while (maintenancedClientsList.isNotEmpty() && maintenancedClientsList.peek().isConnected.get()) {
            jsonRpcTcpClientsList.put(maintenancedClientsList.take())
        }

        removeDeadClients()

        // We should support enough connections in active mode
        if (currentMode == ConnectionManagerMode.ACTIVE) {
            createConnections(connectionsCount - (jsonRpcTcpClientsList.size + maintenancedClientsList.size), endpoints, logger)
        }

        maintainSubscriptions()
    }

    private fun maintainSubscriptions() {
        if (jsonRpcTcpClientsList.isNotEmpty() && subscriptions.isNotEmpty()) {
            synchronized(subscriptions) {
                while (subscriptions.isNotEmpty() && jsonRpcTcpClientsList.isNotEmpty()) {
                    // Most probably would distribute subscriptions between couple of clients.
                    val rpcClient = jsonRpcTcpClientsList.poll()
                    if (rpcClient != null) {
                        val key = subscriptions.keys.first()
                        rpcClient.subscribe(subscriptions[key]!!)
                        subscriptions.remove(key)
                    }
                    jsonRpcTcpClientsList.put(rpcClient)
                }
            }
        }
    }

    private fun removeDeadClients() {
        // If client can't reconnect for too long time we should get rid of it
        val currentTime = System.currentTimeMillis()

        // If app is inactive we should just stop all disconnected clients to not to drain battery.
        val deadClients = (if (currentMode == ConnectionManagerMode.ACTIVE) {
            maintenancedClientsList.filter { currentTime - it.lastSuccessTime > MAX_RECONNECT_INTERVAL }
        } else {
            maintenancedClientsList.filter { !it.isConnected.get() }
        }).toMutableList()

        deadClients.addAll(if (currentMode == ConnectionManagerMode.ACTIVE) {
            jsonRpcTcpClientsList.filter { currentTime - it.lastSuccessTime > MAX_RECONNECT_INTERVAL }
        } else {
            jsonRpcTcpClientsList.filter { !it.isConnected.get() }
        })

        deadClients.forEach {
            it.stop()
            synchronized(subscriptions) {
                subscriptions.putAll(it.getSubscriptions())
            }
        }
        maintenancedClientsList.removeAll(deadClients)
        jsonRpcTcpClientsList.removeAll(deadClients)
    }

    private fun getClient(): JsonRpcTcpClient {
        var rpcClient = jsonRpcTcpClientsList.take()
        while (!rpcClient.isConnected.get()) {
            // If client usage was unsuccessful put into maintained list to not to spend time on it
            maintenancedClientsList.put(rpcClient)
            rpcClient = jsonRpcTcpClientsList.take()
        }
        return rpcClient
    }

    @Volatile
    private var currentMode = ConnectionManagerMode.ACTIVE

    /**
     * There are two possible states of connection manager:
     * 1. [ACTIVE] - screen on, app is in foreground and connected to network. All possible connections
     *    should be established all the time. If one of connections becomes dead - it should be immediately replaced.
     * 2. [PASSIVE] - screen of and/or app is in foreground and/or no network connection. Connections should not be terminated,
     *    but no reconnect attempts should happen.
     */
    private enum class ConnectionManagerMode {
        ACTIVE,
        PASSIVE
    }

    companion object {
        private val MAINTENANCE_INTERVAL = TimeUnit.SECONDS.toMillis(10)
        private val INACTIVE_MAINTENANCE_INTERVAL = TimeUnit.MINUTES.toMillis(2)
        private val MAX_RECONNECT_INTERVAL = TimeUnit.MINUTES.toMillis(10)
        private val MAX_WRITE_TIME = TimeUnit.MINUTES.toMillis(10)
    }
}