package com.houvven.ktx_xposed.logger

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.houvven.ktx_xposed.hook.ModernXposedRuntime
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object XposedLogger {

    private const val TAG_PREFIX = "Guise"
    private const val DEFAULT_CATEGORY = "Runtime"
    private const val MAX_MESSAGE_LENGTH = 1_024
    private const val MAX_STACK_TRACE_LENGTH = 4_096
    private const val DELIVERY_BATCH_SIZE = 16
    private const val DELIVERY_DELAY_MILLIS = 5_000L
    private const val DELIVERY_RETRY_DELAY_MILLIS = 15_000L

    object Level {
        const val DEBUG = 'D'
        const val INFO = 'I'
        const val ERROR = 'E'
    }

    private val sequence = AtomicLong()
    private val deliveryFailureReported = AtomicBoolean()
    private val categoryContext = ThreadLocal<String?>()
    private val pendingEvents = ArrayDeque<RuntimeLogEvent>()
    private var applicationContext: Context? = null
    private var startupCompleted = false
    private var deliveryToken: String? = null
    private var detailedLogging = false
    private var contextProvider: (() -> Context?)? = null
    private val deliveryExecutor by lazy {
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Guise-RuntimeLog").apply { isDaemon = true }
        }
    }
    private var scheduledDelivery: ScheduledFuture<*>? = null

    val currentCategory: String
        get() = categoryContext.get() ?: DEFAULT_CATEGORY

    fun <T> withCategory(category: String, block: () -> T): T {
        val previous = categoryContext.get()
        categoryContext.set(category)
        return try {
            block()
        } finally {
            if (previous == null) categoryContext.remove() else categoryContext.set(previous)
        }
    }

    fun d(msg: String, category: String = DEFAULT_CATEGORY) {
        basicLog(Level.DEBUG, category, msg, null)
    }

    fun i(msg: String, category: String = DEFAULT_CATEGORY) {
        basicLog(Level.INFO, category, msg, null)
    }

    fun e(msg: String, category: String = DEFAULT_CATEGORY) {
        basicLog(Level.ERROR, category, msg, null)
    }

    fun e(throwable: Throwable, category: String = currentCategory) {
        basicLog(Level.ERROR, category, throwable.toString(), throwable)
    }

    @Synchronized
    fun initialize(contextProvider: () -> Context?) {
        this.contextProvider = contextProvider
        val module = ModernXposedRuntime.moduleOrNull ?: return
        val preferences = runCatching {
            module.getRemotePreferences(RuntimeLogProtocol.PREFERENCES_NAME)
        }.getOrNull()
        deliveryToken = preferences?.getString(RuntimeLogProtocol.DELIVERY_TOKEN_KEY, null)
        detailedLogging = preferences?.getBoolean(
            RuntimeLogProtocol.DETAILED_LOGGING_KEY,
            false,
        ) == true
    }

    @Synchronized
    fun finishStartup() {
        startupCompleted = true
        tryAttachContextLocked()
        scheduleDeliveryLocked()
    }

    @Synchronized
    fun needsDeliveryContext(): Boolean =
        deliveryToken != null && pendingEvents.isNotEmpty() && applicationContext == null

    @Synchronized
    fun tryAttachContext(): Boolean {
        tryAttachContextLocked()
        return applicationContext != null
    }

    @Synchronized
    private fun basicLog(level: Char, category: String, msg: String, throwable: Throwable?) {
        val module = ModernXposedRuntime.moduleOrNull ?: return
        val context = ModernXposedRuntime.packageContextOrNull ?: return
        if (level == Level.DEBUG && !detailedLogging) return
        val priority = when (level) {
            Level.DEBUG -> Log.DEBUG
            Level.ERROR -> Log.ERROR
            else -> Log.INFO
        }
        runCatching {
            if (throwable == null) {
                module.log(priority, "$TAG_PREFIX/$category", msg)
            } else {
                module.log(priority, "$TAG_PREFIX/$category", msg, throwable)
            }
        }

        if (deliveryToken == null) return

        val now = System.currentTimeMillis()
        pendingEvents += RuntimeLogEvent(
            id = "$now-${Process.myPid()}-${SystemClock.elapsedRealtimeNanos()}-${sequence.incrementAndGet()}",
            timestamp = now,
            level = level,
            packageName = context.packageName,
            processName = context.processName,
            category = category.take(MAX_MESSAGE_LENGTH),
            message = msg.take(MAX_MESSAGE_LENGTH),
            stackTrace = throwable?.fullStackTrace().orEmpty().take(MAX_STACK_TRACE_LENGTH),
        )
        while (pendingEvents.size > RuntimeLogProtocol.MAX_PENDING_EVENTS) {
            pendingEvents.removeFirst()
        }
        tryAttachContextLocked()
        if (startupCompleted) scheduleDeliveryLocked()
    }

    private fun tryAttachContextLocked() {
        if (applicationContext != null || deliveryToken == null || pendingEvents.isEmpty()) return
        val context = runCatching { contextProvider?.invoke() }.getOrNull() ?: return
        applicationContext = context.applicationContext ?: context
        if (startupCompleted) scheduleDeliveryLocked()
    }

    private fun scheduleDeliveryLocked(delayMillis: Long = DELIVERY_DELAY_MILLIS) {
        if (applicationContext == null || pendingEvents.isEmpty()) return
        if (delayMillis == DELIVERY_DELAY_MILLIS && pendingEvents.size >= DELIVERY_BATCH_SIZE) {
            flushPending()
            return
        }
        if (scheduledDelivery?.isDone == false) return
        scheduledDelivery = deliveryExecutor.schedule(
            { flushPending() },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
    }

    @Synchronized
    private fun flushPending() {
        scheduledDelivery?.cancel(false)
        scheduledDelivery = null
        val context = applicationContext ?: return
        if (pendingEvents.isEmpty()) return
        val snapshot = pendingEvents.toList()
        runCatching {
            val intent = Intent(RuntimeLogProtocol.DELIVERY_ACTION)
                .setComponent(
                    ComponentName(
                        RuntimeLogProtocol.DELIVERY_PACKAGE,
                        RuntimeLogProtocol.DELIVERY_RECEIVER,
                    )
                )
                .putExtra(
                    RuntimeLogProtocol.DELIVERY_EVENTS_EXTRA,
                    RuntimeLogProtocol.encode(snapshot),
                )
                .putExtra(RuntimeLogProtocol.DELIVERY_TOKEN_EXTRA, deliveryToken)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendBroadcast(intent, null, options)
            } else {
                context.sendBroadcast(intent)
            }
        }.onSuccess {
            repeat(snapshot.size.coerceAtMost(pendingEvents.size)) {
                pendingEvents.removeFirst()
            }
            deliveryFailureReported.set(false)
        }.onFailure { error ->
            val module = ModernXposedRuntime.moduleOrNull
            if (module != null && deliveryFailureReported.compareAndSet(false, true)) {
                runCatching {
                    module.log(Log.WARN, "$TAG_PREFIX/Logger", "Unable to deliver runtime logs", error)
                }
            }
            scheduleDeliveryLocked(DELIVERY_RETRY_DELAY_MILLIS)
        }
    }

    private fun Throwable.fullStackTrace(): String = StringWriter().also { writer ->
        printStackTrace(PrintWriter(writer))
    }.toString()
}
