package dev.forkhandles.transactions

import dev.forkhandles.result4k.Failure
import dev.forkhandles.result4k.Result
import dev.forkhandles.result4k.Success
import dev.forkhandles.transactions.ChainableActions.Cause
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

interface Controls {
    fun pause()
    fun resume()
}

interface FailureControls {
    val tries: Int

    fun retry(): Nothing
}

class Processor(private val actions: List<Action>) {
    private val tries = AtomicInteger(0)
    private val running = AtomicBoolean(false)

    private val iterator = actions.listIterator()

//    private var cursor = AtomicInteger(0)

    private var result: Result<Unit, Throwable> = Failure(Exception("Nothing done"))

    private val controls = object : Controls {
        override fun pause() {
            running.set(false)
        }

        override fun resume() {
            running.set(true)
        }
    }

    private val failureRunning = AtomicBoolean(false)
    private val failureControls = object : FailureControls {
        override val tries: Int
            get() = this@Processor.tries.get()

        override fun retry(): Nothing {
            launch()
            throw Exception("")
        }
    }

    fun launch(): Result<Unit, Throwable> {
        tries.incrementAndGet()
        running.set(true)
        failureRunning.set(false)

        try {
            while (running.get() && iterator.hasNext()) {
                iterator.next().fn(controls)
            }

            result = Success(Unit)
        } catch (e: Throwable) {
            result = Failure(e)
            rollback()
        }

        return result
    }

    private fun rollback() {
        running.set(false)
        failureRunning.set(true)

        iterator.previous()

        while (failureRunning.get() && iterator.hasPrevious()) {
            val action = iterator.previous()

            runCatching {
                action.rollback(failureControls)
            }
        }
    }

    data class Action(val name: String, val fn: Controls.() -> Unit, val rollback: FailureControls.() -> Unit)
}

class ChainableActions private constructor(private val actions: List<Action>) {
    fun execute(): Result<Unit, Cause> {
        val running = AtomicBoolean(false)
        val controls = object : Controls {
            override fun pause() {
                running.set(false)
            }

            override fun resume() {
                running.set(true)
            }
        }

        val executedActions = mutableListOf<Action>()

        return try {
            val iterator = actions.iterator()

            running.set(true)

            while (iterator.hasNext()) {
                if (!running.get()) continue

                val action = iterator.next()
                action.fn(controls)
                executedActions.add(action)
            }

            Success(Unit)
        } catch (e: Throwable) {
            handleException(e, executedActions)
        }
    }

    private fun handleException(
        e: Throwable,
        executedActions: List<Action>,
    ): Failure<Cause> {
        val lastAction = executedActions.lastOrNull()

        val iterator = executedActions.reversed().iterator()

        while (iterator.hasNext()) {
            iterator.next().rollback()
        }

        return Failure(
            Cause(
                name = lastAction?.name,
                step = executedActions.lastIndex,
                error = e,
            )
        )
    }

    class Builder {
        private val actions = mutableListOf<Action>()

        fun action(name: String, fn: Controls.() -> Unit, rollback: () -> Unit) {
            actions.add(Action(name, fn, rollback))
        }

        fun action(name: String, fn: Controls.() -> Unit) {
            action(name, fn) {}
        }

        fun build(): ChainableActions = ChainableActions(actions)
    }

    interface Controls {
        fun pause()
        fun resume()
    }

    private data class Action(val name: String, val fn: Controls.() -> Unit, val rollback: () -> Unit)
    data class Cause(val name: String?, val step: Int, val error: Throwable)
}

fun chainableActions(fn: ChainableActions.Builder.() -> Unit): ChainableActions =
    ChainableActions.Builder().apply(fn).build()

fun chainActions(fn: ChainableActions.Builder.() -> Unit): Result<Unit, Cause> =
    chainableActions(fn).execute()

fun main() {
//    chainActions {
//        action(
//            name = "aaa",
//            fn = {
//                println("aaa")
//
//                pause()
//
//                CompletableFuture.runAsync {
//                    Thread.sleep(2000)
//
//                    resume()
//                }
//            },
//            rollback = {
//                println("rollback aaa")
//            }
//        )
//
//        action("bbb") {
//            println("bbb")
//        }
//    }

    Processor(
        listOf(
            Processor.Action(
                name = "",
                fn = {
                    pause()
                    println("")
                    println("aaa")
                    resume()
                },
                rollback = {
                    println("aaa rollback $tries")
                    if (tries < 10) {
                        retry()
                        println("after retry")
                    }
                },
            ),
            Processor.Action(
                name = "",
                fn = {
                    println("bbb")
                },
                rollback = {
                    println("bbb rollback $tries")
                },
            ),
            Processor.Action(
                name = "",
                fn = {
                    println("ccc")
                    println("failure")
                    throw Exception("Failure")
                },
                rollback = {
                    println("ccc rollback $tries")
                },
            ),
        ),
    ).launch().let { println(it) }
}
