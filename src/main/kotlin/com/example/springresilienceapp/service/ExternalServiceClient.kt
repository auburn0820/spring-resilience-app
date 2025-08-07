package com.example.springresilienceapp.service

import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.CompletableFuture
import kotlin.random.Random

@Service
class ExternalServiceClient {

  private val logger = LoggerFactory.getLogger(ExternalServiceClient::class.java)

  @CircuitBreaker(name = "core-user-service", fallbackMethod = "fallbackUserService")
  @Retry(name = "core-user-service")
  @Bulkhead(name = "core-user-service")
  fun callUserService(userId: String): Map<String, Any> {
    logger.info("Calling core user service for: {}", userId)

    // 실제로는 HTTP 호출
    // val response = restTemplate.getForObject("http://user-service/users/{userId}", String::class.java, userId)

    simulateServiceCall(300, 0.5) // 50% 실패율

    return mapOf(
      "userId" to userId,
      "name" to "User $userId",
      "email" to "user$userId@example.com",
      "status" to "active"
    )
  }

  @CircuitBreaker(name = "payment-gateway", fallbackMethod = "fallbackPaymentGateway")
  @Retry(name = "payment-gateway")
  @Bulkhead(name = "payment-gateway", type = Bulkhead.Type.THREADPOOL)
  fun callPaymentGateway(paymentRequest: Map<String, Any>): CompletableFuture<Map<String, Any>> {
    logger.info("Calling payment gateway for: {}", paymentRequest["paymentId"])

    return CompletableFuture.supplyAsync {
      simulateServiceCall(800, 0.40) // 40% 실패율 (외부 서비스)

      mapOf(
        "paymentId" to (paymentRequest["paymentId"] ?: "unknown"),
        "status" to "completed",
        "transactionId" to "TXN-${System.currentTimeMillis()}",
        "amount" to (paymentRequest["amount"] ?: 0)
      )
    }
  }

  @CircuitBreaker(name = "redis-cache", fallbackMethod = "fallbackRedisCache")
  @Retry(name = "redis-cache")
  fun callRedisCache(key: String): String? {
    logger.info("Calling Redis cache for key: {}", key)

    simulateServiceCall(50, 0.10) // 10% 실패율

    return if (Random.nextBoolean()) "cached-value-$key" else null
  }

  @CircuitBreaker(name = "third-party-api", fallbackMethod = "fallbackThirdPartyApi")
  @Retry(name = "third-party-api")
  @Bulkhead(name = "third-party-api")
  fun callThirdPartyApi(requestId: String): Map<String, Any> {
    logger.info("Calling third-party API for: {}", requestId)

    simulateServiceCall(1200, 0.35) // 35% 실패율, 느린 응답

    return mapOf(
      "requestId" to requestId,
      "data" to "Third party data for $requestId",
      "provider" to "external-api-v2",
      "timestamp" to System.currentTimeMillis()
    )
  }

  @CircuitBreaker(name = "inventory-service", fallbackMethod = "fallbackInventoryService")
  @Retry(name = "inventory-service")
  fun callInventoryService(productId: String, quantity: Int): Map<String, Any> {
    logger.info("Calling inventory service for product: {} quantity: {}", productId, quantity)

    simulateServiceCall(200, 0.20) // 20% 실패율

    val available = Random.nextInt(0, 100)
    return mapOf(
      "productId" to productId,
      "requestedQuantity" to quantity,
      "availableStock" to available,
      "reserved" to minOf(quantity, available),
      "status" to if (available >= quantity) "available" else "insufficient"
    )
  }

  @CircuitBreaker(name = "notification-service", fallbackMethod = "fallbackNotificationService")
  @Retry(name = "notification-service")
  fun callNotificationService(userId: String, message: String, channel: String): Map<String, Any> {
    logger.info("Sending {} notification to user: {}", channel, userId)

    simulateServiceCall(100, 0.8) // 80% 실패율로 Circuit Breaker 테스트

    return mapOf(
      "userId" to userId,
      "message" to message,
      "channel" to channel,
      "status" to "sent",
      "messageId" to "MSG-${System.currentTimeMillis()}"
    )
  }

  @CircuitBreaker(name = "log-service", fallbackMethod = "fallbackLogService")
  fun callLogService(logData: Map<String, Any>): Boolean {
    logger.info("Sending log data: {}", logData["eventType"])

    simulateServiceCall(50, 0.45) // 45% 실패율이어도 괜찮음

    return true
  }

  fun fallbackUserService(userId: String, ex: Exception): Map<String, Any> {
    logger.warn("User service fallback for: {} - {}", userId, ex.message)
    return mapOf(
      "userId" to userId,
      "name" to "Unknown User",
      "status" to "fallback",
      "error" to "User service unavailable"
    )
  }

  fun fallbackPaymentGateway(paymentRequest: Map<String, Any>, ex: Exception): CompletableFuture<Map<String, Any>> {
    logger.warn("Payment gateway fallback for: {} - {}", paymentRequest["paymentId"], ex.message)
    return CompletableFuture.completedFuture(
      mapOf(
        "paymentId" to (paymentRequest["paymentId"] ?: "unknown"),
        "status" to "pending",
        "message" to "Payment queued for retry",
        "error" to true
      )
    )
  }

  fun fallbackRedisCache(key: String, ex: Exception): String? {
    logger.warn("Redis cache fallback for key: {} - {}", key, ex.message)
    return null // 캐시 실패시 null 반환
  }

  fun fallbackThirdPartyApi(requestId: String, ex: Exception): Map<String, Any> {
    logger.warn("Third-party API fallback for: {} - {}", requestId, ex.message)
    return mapOf(
      "requestId" to requestId,
      "data" to "Fallback data - service unavailable",
      "error" to true,
      "fallback" to true
    )
  }

  fun fallbackInventoryService(productId: String, quantity: Int, ex: Exception): Map<String, Any> {
    logger.warn("Inventory service fallback for product: {} - {}", productId, ex.message)
    return mapOf(
      "productId" to productId,
      "requestedQuantity" to quantity,
      "availableStock" to 0,
      "status" to "error",
      "message" to "Inventory service unavailable"
    )
  }

  fun fallbackNotificationService(userId: String, message: String, channel: String, ex: Exception): Map<String, Any> {
    logger.warn("Notification service fallback for user: {} - {}", userId, ex.message)
    return mapOf(
      "userId" to userId,
      "message" to message,
      "channel" to channel,
      "status" to "failed",
      "fallback" to true
    )
  }

  fun fallbackLogService(logData: Map<String, Any>, ex: Exception): Boolean {
    logger.warn("Log service fallback - {}", ex.message)
    // 로그는 실패해도 큰 문제 없으므로 그냥 무시
    return false
  }

  fun processComplexOrder(
    userId: String,
    productId: String,
    quantity: Int,
    paymentData: Map<String, Any>
  ): Map<String, Any> {
    val result = mutableMapOf<String, Any>()

    try {
      // 1. 사용자 정보 조회 (핵심 서비스)
      val user = callUserService(userId)
      result["user"] = user

      // 2. 캐시에서 상품 정보 조회 시도
      val cachedProduct = callRedisCache("product:$productId")
      result["cachedProduct"] = cachedProduct ?: "not cached"

      // 3. 재고 확인
      val inventory = callInventoryService(productId, quantity)
      result["inventory"] = inventory

      if (inventory["status"] == "available") {
        // 4. 결제 처리 (비동기)
        val paymentFuture = callPaymentGateway(paymentData)

        // 5. 써드파티 API 호출 (상품 정보 보강)
        val thirdPartyData = callThirdPartyApi("product-info-$productId")
        result["productDetails"] = thirdPartyData

        // 6. 결제 완료 대기
        val payment = paymentFuture.get()
        result["payment"] = payment

        if (payment["status"] == "completed") {
          // 7. 알림 발송 (실패해도 괜찮음)
          val notification = callNotificationService(userId, "Order completed", "email")
          result["notification"] = notification

          // 8. 로그 기록 (실패해도 괜찮음)
          val logSent = callLogService(
            mapOf(
              "eventType" to "order_completed",
              "userId" to userId,
              "productId" to productId
            )
          )
          result["logSent"] = logSent

          result["status"] = "SUCCESS"
        } else {
          result["status"] = "PAYMENT_FAILED"
        }
      } else {
        result["status"] = "INSUFFICIENT_INVENTORY"
      }

    } catch (e: Exception) {
      logger.error("Complex order processing failed: {}", e.message)
      result["status"] = "ERROR"
      result["error"] = (e.message ?: "Unknown error")
    }

    return result
  }

  private fun simulateServiceCall(delayMs: Long, failureRate: Double) {
    Thread.sleep(Random.nextLong(delayMs / 2, delayMs))

    if (Random.nextDouble() < failureRate) {
      throw RuntimeException("Service temporarily unavailable")
    }
  }
}