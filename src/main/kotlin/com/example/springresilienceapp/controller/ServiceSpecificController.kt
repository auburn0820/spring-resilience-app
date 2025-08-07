package com.example.springresilienceapp.controller

import com.example.springresilienceapp.service.ExternalServiceClient
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.concurrent.CompletableFuture

@RestController
@RequestMapping("/api/services")
class ServiceSpecificController(
    private val externalServiceClient: ExternalServiceClient,
    private val circuitBreakerRegistry: CircuitBreakerRegistry
) {

    @GetMapping("/user/{userId}")
    fun getUser(@PathVariable userId: String): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.callUserService(userId)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/payment")
    fun processPayment(@RequestBody paymentRequest: Map<String, Any>): CompletableFuture<ResponseEntity<Map<String, Any>>> {
        return externalServiceClient.callPaymentGateway(paymentRequest)
            .thenApply { result -> ResponseEntity.ok(result) }
            .exceptionally { throwable ->
                ResponseEntity.internalServerError().body(mapOf("error" to (throwable.message ?: "Payment failed")))
            }
    }

    @GetMapping("/cache/{key}")
    fun getCacheValue(@PathVariable key: String): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.callRedisCache(key)
        return ResponseEntity.ok(mapOf(
            "key" to key,
            "value" to (result ?: "null"),
            "cached" to (result != null)
        ))
    }

    @GetMapping("/external/{requestId}")
    fun callThirdParty(@PathVariable requestId: String): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.callThirdPartyApi(requestId)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/inventory/{productId}")
    fun checkInventory(
        @PathVariable productId: String,
        @RequestParam(defaultValue = "1") quantity: Int
    ): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.callInventoryService(productId, quantity)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/notify")
    fun sendNotification(
        @RequestParam userId: String,
        @RequestParam message: String,
        @RequestParam(defaultValue = "email") channel: String
    ): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.callNotificationService(userId, message, channel)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/log")
    fun sendLog(@RequestBody logData: Map<String, Any>): ResponseEntity<Map<String, Any>> {
        return try {
            val result = externalServiceClient.callLogService(logData)
            ResponseEntity.ok(mapOf("logged" to result))
        } catch (e: Exception) {
            ResponseEntity.ok(mapOf("logged" to false, "reason" to (e.message ?: "Log service failed")))
        }
    }

    @PostMapping("/complex-order")
    fun processComplexOrder(
        @RequestParam userId: String,
        @RequestParam productId: String,
        @RequestParam quantity: Int,
        @RequestBody paymentData: Map<String, Any>
    ): ResponseEntity<Map<String, Any>> {
        val result = externalServiceClient.processComplexOrder(userId, productId, quantity, paymentData)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/circuit-status")
    fun getCircuitBreakerStatus(): ResponseEntity<Map<String, Any>> {
        val services = listOf(
            "core-user-service",
            "payment-gateway",
            "redis-cache",
            "third-party-api",
            "inventory-service",
            "notification-service",
            "log-service"
        )

        val status = services.associate { serviceName ->
            val cb = circuitBreakerRegistry.circuitBreaker(serviceName)
            serviceName to mapOf(
                "state" to cb.state.name,
                "failureRate" to cb.metrics.failureRate,
                "slowCallRate" to cb.metrics.slowCallRate,
                "numberOfBufferedCalls" to cb.metrics.numberOfBufferedCalls,
                "numberOfFailedCalls" to cb.metrics.numberOfFailedCalls,
                "numberOfSlowCalls" to cb.metrics.numberOfSlowCalls
            )
        }

        return ResponseEntity.ok(mapOf(
            "circuitBreakers" to status,
            "summary" to mapOf(
                "total" to services.size,
                "open" to status.values.count { (it["state"] as String) == "OPEN" },
                "closed" to status.values.count { (it["state"] as String) == "CLOSED" },
                "halfOpen" to status.values.count { (it["state"] as String) == "HALF_OPEN" }
            )
        ))
    }

    // 테스트용 - 서비스별 부하 생성
    @PostMapping("/load-test/{serviceName}")
    fun loadTestService(
        @PathVariable serviceName: String,
        @RequestParam(defaultValue = "10") requests: Int
    ): ResponseEntity<Map<String, Any>> {
        val futures = (1..requests).map { index ->
            CompletableFuture.supplyAsync {
                try {
                    when (serviceName) {
                        "user" -> externalServiceClient.callUserService("user-$index")
                        "cache" -> mapOf("result" to externalServiceClient.callRedisCache("test-key-$index"))
                        "inventory" -> externalServiceClient.callInventoryService("product-$index", index)
                        "notification" -> externalServiceClient.callNotificationService("user-$index", "Test message", "email")
                        "external" -> externalServiceClient.callThirdPartyApi("request-$index")
                        else -> mapOf("error" to "Unknown service")
                    }
                    "success"
                } catch (e: Exception) {
                    "failure: ${e.message}"
                }
            }
        }

        val results = CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                futures.map { it.get() }.groupBy(
                    { if (it.startsWith("success")) "success" else "failure" },
                    { it }
                )
            }
            .get()

        return ResponseEntity.ok(mapOf(
            "service" to serviceName,
            "totalRequests" to requests,
            "successCount" to (results["success"]?.size ?: 0),
            "failureCount" to (results["failure"]?.size ?: 0),
            "successRate" to "${((results["success"]?.size ?: 0) * 100.0 / requests).toInt()}%"
        ))
    }
}