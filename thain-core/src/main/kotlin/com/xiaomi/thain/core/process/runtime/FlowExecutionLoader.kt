package com.xiaomi.thain.core.process.runtime

import com.xiaomi.thain.common.constant.FlowExecutionStatus
import com.xiaomi.thain.common.constant.FlowLastRunStatus
import com.xiaomi.thain.common.exception.*
import com.xiaomi.thain.common.model.dp.AddFlowExecutionDp
import com.xiaomi.thain.common.model.dr.FlowExecutionDr
import com.xiaomi.thain.common.utils.HostUtils
import com.xiaomi.thain.core.constant.FlowExecutionTriggerType
import com.xiaomi.thain.core.process.ProcessEngineStorage
import com.xiaomi.thain.core.process.runtime.executor.FlowExecutor
import com.xiaomi.thain.core.thread.pool.ThainThreadPool
import org.apache.commons.lang3.exception.ExceptionUtils
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * @author liangyongrui
 */
class FlowExecutionLoader(private val processEngineStorage: ProcessEngineStorage) {
    private val log = LoggerFactory.getLogger(this.javaClass)!!

    val runningFlowExecution: MutableSet<FlowExecutionDr> = ConcurrentHashMap.newKeySet()

    private val flowExecutionWaitingQueue = processEngineStorage.flowExecutionWaitingQueue
    private val flowExecutionThreadPool = processEngineStorage.flowExecutionThreadPool
    private val flowDao = processEngineStorage.flowDao
    private val idleThread = LinkedBlockingQueue<Boolean>()

    private fun loopLoader() {
        while (true) {
            try {
                val flowExecutionDr = flowExecutionWaitingQueue.take()
                try {
                    checkFlowRunStatus(flowExecutionDr)
                } catch (e: Exception) {
                    log.warn(e.message)
                    continue
                }
                idleThread.take()
                CompletableFuture.runAsync(Runnable {
                    try {
                        runFlowExecution(flowExecutionDr, 0)
                    } finally {
                        idleThread.put(true)
                    }
                }, flowExecutionThreadPool)
            } catch (e: Exception) {
                log.error("", e)
                processEngineStorage.mailService.sendSeriousError(ExceptionUtils.getStackTrace(e))
            }
        }
    }

    private fun checkFlowRunStatus(flowExecutionDr: FlowExecutionDr) {
        val flowModel = flowDao.getFlow(flowExecutionDr.flowId).orElseThrow {
            processEngineStorage.flowExecutionDao.updateFlowExecutionStatus(flowExecutionDr.id, FlowExecutionStatus.KILLED.code)
            ThainException("flow does not exist")
        }
        val flowLastRunStatus = FlowLastRunStatus.getInstance(flowModel.lastRunStatus)
        if (flowLastRunStatus == FlowLastRunStatus.RUNNING) {
            processEngineStorage.flowExecutionDao.updateFlowExecutionStatus(flowExecutionDr.id, FlowExecutionStatus.DO_NOT_RUN_SAME_TIME.code)
            throw ThainFlowRunningException(flowExecutionDr.flowId)
        }
    }

    private fun runFlowExecution(flowExecutionDr: FlowExecutionDr, retryNumber: Int) {
        try {
            runningFlowExecution.add(flowExecutionDr)
            FlowExecutor(flowExecutionDr, processEngineStorage, retryNumber).start()
        } catch (e: Exception) {
            log.error("runFlowExecution: ", e)
        } finally {
            runningFlowExecution.remove(flowExecutionDr)
        }
    }

    @Throws(ThainException::class, ThainRepeatExecutionException::class)
    fun startAsync(flowId: Long): Long {
        val addFlowExecutionDp = AddFlowExecutionDp.builder()
                .flowId(flowId)
                .hostInfo(HostUtils.getHostInfo())
                .status(FlowExecutionStatus.WAITING.code)
                .triggerType(FlowExecutionTriggerType.MANUAL.code)
                .build()
        processEngineStorage.flowExecutionDao.addFlowExecution(addFlowExecutionDp)
        if (addFlowExecutionDp.id == null) {
            throw ThainCreateFlowExecutionException()
        }
        val flowExecutionDr = processEngineStorage.flowExecutionDao
                .getFlowExecution(addFlowExecutionDp.id!!).orElseThrow { ThainRuntimeException() }
        checkFlowRunStatus(flowExecutionDr)
        CompletableFuture.runAsync(Runnable { runFlowExecution(flowExecutionDr, 0) },
                ThainThreadPool.MANUAL_TRIGGER_THREAD_POOL)
        return addFlowExecutionDp.id!!
    }

    fun retryAsync(flowId: Long, retryNumber: Int): Long {
        val addFlowExecutionDp = AddFlowExecutionDp.builder()
                .flowId(flowId)
                .hostInfo(HostUtils.getHostInfo())
                .status(FlowExecutionStatus.WAITING.code)
                .triggerType(FlowExecutionTriggerType.RETRY.code)
                .build()
        processEngineStorage.flowExecutionDao.addFlowExecution(addFlowExecutionDp)
        if (addFlowExecutionDp.id == null) {
            throw ThainCreateFlowExecutionException()
        }
        val flowExecutionDr = processEngineStorage.flowExecutionDao
                .getFlowExecution(addFlowExecutionDp.id!!).orElseThrow { ThainRuntimeException() }
        CompletableFuture.runAsync(Runnable { runFlowExecution(flowExecutionDr, retryNumber) },
                ThainThreadPool.RETRY_THREAD_POOL)
        return addFlowExecutionDp.id!!
    }

    init {
        repeat(flowExecutionThreadPool.corePoolSize()) { idleThread.put(true) }
        log.info("init FlowExecutionLoader, idleThread size: {}", idleThread.size)
        ThainThreadPool.DEFAULT_THREAD_POOL.execute { loopLoader() }
    }
}
