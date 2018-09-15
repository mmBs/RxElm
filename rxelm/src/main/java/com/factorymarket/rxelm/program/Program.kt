package com.factorymarket.rxelm.program

import com.factorymarket.rxelm.cmd.BatchCmd
import com.factorymarket.rxelm.cmd.Cmd
import com.factorymarket.rxelm.cmd.None
import com.factorymarket.rxelm.cmd.SwitchCmd
import com.factorymarket.rxelm.contract.Component
import com.factorymarket.rxelm.contract.RenderableComponent
import com.factorymarket.rxelm.msg.Msg
import com.factorymarket.rxelm.contract.State
import com.factorymarket.rxelm.log.RxElmLogger
import com.factorymarket.rxelm.msg.ErrorMsg
import com.factorymarket.rxelm.msg.Idle
import com.factorymarket.rxelm.sub.RxElmSubscriptions
import com.jakewharton.rxrelay2.BehaviorRelay
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.ArrayDeque

/**
 * How to use these class:
 *
 *
 * All interactions happen in cycle:
 *
 * [Msg]
 * -> update(Message, State)[Component.update] : [Pair]<[State], [Cmd]>
 * -> (Optional)render(State)[Component.render]
 * -> call(Command)[Component.call]
 * -> [Msg].
 *
 *
 * Messages are being passed to [Program] using [accept(Message)][accept] method.
 *
 * Function [render()][RenderableComponent.render] renders view in declarative style according to [State].
 * No other changes of View can happen outside of this function
 *
 * All changes of state must be made only in function [Update][Component.update], which is a pure function.
 * There cannot happen any calls to side effects, like IO work, HTTP requests, etc
 * All user interactions are processed through inheritances of Msg class.
 * Function [Update][Component.update] returns new State with changed fields and [Command][Cmd].
 *
 * Class [Cmd] represents desired Side Effect. If you want do some side effect,
 * you return a [Command][Cmd] from [Update()][Component.update] method
 * and in function [Call][Component.call] do the side effect itself.
 * Results wrapped in resulting [Msg] go to [Update][Component.update] method.
 *
 * Program executes [Commands][Cmd] in [flatMap][Observable.flatMap], that means
 * they will be executed in parallel in [io() scheduler][Schedulers.io].
 * If you want to cancel current command when queueing new one,
 * you must send [Command][Cmd] which inherits [SwitchCmd], this will
 * do all side effects in rx [switchMap][Observable.switchMap] operator
 *
 * @param outputScheduler the scheduler to [observe on][Observable.observeOn]
 */
class Program<S : State> internal constructor(
    val outputScheduler: Scheduler,
    private val logger: RxElmLogger?,
    private val handleCmdErrors: Boolean,
    private val component: Component<S>
) {

    private val msgRelay: BehaviorRelay<Msg> = BehaviorRelay.create()
    private val cmdRelay: BehaviorRelay<Cmd> = BehaviorRelay.create()
    private val switchRelay: BehaviorRelay<SwitchCmd> = BehaviorRelay.create()

    // Here messages are kept until they can be passed to msgRelay
    private var msgQueue = ArrayDeque<Msg>()

    /** State at this moment */
    lateinit var state: S
        private set

    private var lock: Boolean = false
    var rxElmSubscriptions: RxElmSubscriptions<S>? = null

    fun init(initialState: S, rxElmSubscriptions: RxElmSubscriptions<S>): Disposable {
        this.rxElmSubscriptions = rxElmSubscriptions
        return init(initialState)
    }

    fun init(initialState: S): Disposable {
        this.state = initialState

        val disposable = msgRelay
            .observeOn(outputScheduler)
            .map { msg ->
                if (logger?.showLog() == true) {
                    logger.log(this.state.javaClass.simpleName, "reduce msg:${msg.javaClass.simpleName} ")
                }
                val (newState, command) = component.update(msg, this.state)

                if (msgQueue.size > 0) {
                    msgQueue.removeFirst()
                }

                if (component is RenderableComponent && newState !== this.state) {
                    component.render(newState)
                }
                this.state = newState
                lock = false
                this.rxElmSubscriptions?.subscribe(this)
                pickNextMessageFromQueue()
                return@map command
            }
            .filter { cmd -> cmd !== None }
            .subscribe { cmd ->
                if (cmd is SwitchCmd) {
                    switchRelay.accept(cmd)
                } else {
                    cmdRelay.accept(cmd)
                }
            }

        handleResponse(cmdRelay.flatMap { cmd ->
            if (logger?.showLog() == true) {
                logger.log(this.state.javaClass.simpleName, "elm call cmd:$cmd")
            }
            call(cmd).subscribeOn(Schedulers.io())
        })

        handleResponse(switchRelay.switchMap { cmd ->
            if (logger?.showLog() == true) {
                logger.log(this.state.javaClass.simpleName, "elm call cmd:$cmd")
            }
            call(cmd).subscribeOn(Schedulers.io())
        })

        return disposable
    }

    private fun handleResponse(observable: Observable<Msg>) {
        observable.observeOn(outputScheduler)
            .subscribe { msg ->
                when (msg) {
                    is Idle -> {
                    }
                    else -> msgQueue.addLast(msg)
                }

                pickNextMessageFromQueue()
            }
    }

    fun call(cmd: Cmd): Observable<Msg> {
        return when (cmd) {
            is BatchCmd ->
                Observable.merge(cmd.cmds.map {
                    cmdCall(it)
                })
            else -> cmdCall(cmd)
        }
    }

    private fun cmdCall(cmd: Cmd): Observable<Msg> {
        return if (handleCmdErrors) {
            component.call(cmd)
                .onErrorResumeNext { err -> Single.just(ErrorMsg(err, cmd)) }
                .toObservable()
        } else {
            component.call(cmd)
                .toObservable()
        }
    }


    private fun pickNextMessageFromQueue() {
        if (logger?.showLog() == true) {
            logger.log(this.state.javaClass.simpleName, "pickNextMessageFromQueue, queue size:${msgQueue.size}")
        }
        if (!lock && msgQueue.size > 0) {
            lock = true
            msgRelay.accept(msgQueue.first)
        }
    }

    fun render() {
        if (component is RenderableComponent) {
            component.render(this.state)
        }
    }

    fun accept(msg: Msg) {
        if (logger?.showLog() == true) {
            logger.log(
                this.state.javaClass.simpleName,
                "accept msg: ${msg.javaClass.simpleName}, queue size:${msgQueue.size} lock:$lock "
            )
        }
        msgQueue.addLast(msg)
        if (!lock && msgQueue.size == 1) {
            lock = true
            msgRelay.accept(msgQueue.first)
        }
    }

    fun addEventObservable(eventSource: Observable<Msg>): Disposable {
        return eventSource.subscribe { msg -> accept(msg) }
    }

    fun dispose() {
        rxElmSubscriptions?.dispose()
    }
}