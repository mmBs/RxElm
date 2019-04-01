package com.factorymarket.rxelm.program

import com.factorymarket.rxelm.cmd.BatchCmd
import com.factorymarket.rxelm.cmd.CancelByClassCmd
import com.factorymarket.rxelm.cmd.CancelCmd
import com.factorymarket.rxelm.cmd.Cmd
import com.factorymarket.rxelm.cmd.None
import com.factorymarket.rxelm.cmd.SwitchCmd
import com.factorymarket.rxelm.contract.Component
import com.factorymarket.rxelm.contract.Renderable
import com.factorymarket.rxelm.contract.RenderableComponent
import com.factorymarket.rxelm.msg.Msg
import com.factorymarket.rxelm.contract.State
import com.factorymarket.rxelm.log.LogType
import com.factorymarket.rxelm.log.RxElmLogger
import com.factorymarket.rxelm.msg.ErrorMsg
import com.factorymarket.rxelm.msg.Idle
import com.factorymarket.rxelm.msg.Init
import com.factorymarket.rxelm.sub.RxElmSubscriptions
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.Relay
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.reflect.KClass

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

    private val messageRelay: BehaviorRelay<Msg> = BehaviorRelay.create()
    private val commandsDisposablesMap: MutableMap<Int, MutableMap<Int, Disposable>> = TreeMap()
    private val switchRelayHolder: HashMap<String, Relay<SwitchCmd>> = HashMap()

    /** Here messages are kept until they can be passed to messageRelay */
    private var messageQueue = ArrayDeque<Msg>()

    /** State at this moment */
    private lateinit var state: S

    private var lock: Boolean = false
    private var isRendering: Boolean = false
    private var rxElmSubscriptions: RxElmSubscriptions<S>? = null
    private var disposables: CompositeDisposable = CompositeDisposable()

    fun run(initialState: S, rxElmSubscriptions: RxElmSubscriptions<S>? = null, initialMsg: Msg = Init) {
        init(initialState, rxElmSubscriptions)

        accept(initialMsg)
    }

    fun run(initialState: S, rxElmSubscriptions: RxElmSubscriptions<S>? = null, initialMsgs: List<Msg>) {
        init(initialState, rxElmSubscriptions)

        initialMsgs.forEach {
            accept(it)
        }
    }

    private fun init(initialState: S, rxElmSubscriptions: RxElmSubscriptions<S>?) {
        this.state = initialState
        this.rxElmSubscriptions = rxElmSubscriptions

        val loopDisposable = createLoop(component, logger)

        disposables.add(loopDisposable)
    }

    fun createLoop(component: Component<S>, logger: RxElmLogger?): Disposable {
        return messageRelay
            .observeOn(outputScheduler)
            .map { msg ->

                val (newState, command) = update(msg, component, logger)

                if (newState !== this.state) {
                    isRendering = true
                    if (component is Renderable<*>) {
                        (component as Renderable<S>).render(newState)
                    } else if (component is RenderableComponent) {
                        component.render(newState)
                    }
                    isRendering = false
                }

                this.state = newState
                lock = false

                this.rxElmSubscriptions?.subscribe(this, newState)

                pickNextMessageFromQueue()

                return@map command
            }
            .filter { cmd -> cmd !== None }
            .subscribe { cmd ->
                if (cmd is BatchCmd) {
                    cmd.cmds.forEach { innerCmd ->
                        if (innerCmd is None) {
                            return@forEach
                        }
                        executeCommand(innerCmd)
                    }
                } else {
                    executeCommand(cmd)
                }

            }
    }

    private fun executeCommand(cmd: Cmd) {
        when (cmd) {
            is SwitchCmd -> {
                val relay = getSwitchRelay(cmd)
                relay.accept(cmd)
            }
            is CancelCmd -> {
                val commandDisposablesMap = commandsDisposablesMap[cmd.cancelCmd.hashCode()] ?: return

                val commandDisposables = commandDisposablesMap[cmd.cancelCmd.hashCode()]
                if (commandDisposables != null && !commandDisposables.isDisposed) {
                    logger?.takeIf { logger.logType().needToShowCommands() }?.let {
                        logger.log(this.state.javaClass.simpleName, "elm cancel cmd:${cmd.cancelCmd}")
                    }
                    commandDisposables.dispose()
                }
            }
            is CancelByClassCmd<*> -> {
                val commandDisposablesMap = commandsDisposablesMap[cmd.cmdClass.hashCode()] ?: return
                commandDisposablesMap.values.forEach { disposable ->
                    if (!disposable.isDisposed) {
                        disposable.dispose()
                    }
                }
            }
            else -> handleCmd(cmd)
        }
    }

    private fun handleCmd(cmd: Cmd) {
        logger?.takeIf { logger.logType().needToShowCommands() }?.let {
            logger.log(this.state.javaClass.simpleName, "elm call cmd:$cmd")
        }

        val cmdObservable = cmdCall(cmd).subscribeOn(Schedulers.io())
        val disposable = handleResponse(cmdObservable)
        val cmdDisposablesMap = commandsDisposablesMap[cmd::class.hashCode()]
        cmdDisposablesMap?.let {
            val oldDisposable = cmdDisposablesMap[cmd.hashCode()]
            if (oldDisposable != null && !oldDisposable.isDisposed) {
                disposables.add(oldDisposable)
            }
            cmdDisposablesMap[cmd.hashCode()] = disposable
        } ?: run {
            val disposablesMap = TreeMap<Int, Disposable>()
            disposablesMap[cmd.hashCode()] = disposable
            commandsDisposablesMap[cmd::class.hashCode()] = disposablesMap
        }
    }

    private fun getSwitchRelay(cmd: SwitchCmd): Relay<SwitchCmd> {
        val cmdName = cmd.javaClass::getSimpleName.toString()
        var relay: Relay<SwitchCmd>? = switchRelayHolder[cmdName]
        if (relay == null) {
            relay = BehaviorRelay.create()
            switchRelayHolder[cmdName] = relay
            subscribeSwitchRelay(relay)
        }
        return relay
    }

    fun isRendering() : Boolean = isRendering

    private fun subscribeSwitchRelay(relay: BehaviorRelay<SwitchCmd>) {
        val switchDisposable = handleResponse(relay.switchMap { cmd ->
            logger?.takeIf { it.logType().needToShowCommands() }
                ?.log(this.state.javaClass.simpleName, "elm call cmd:$cmd")

            cmdCall(cmd).subscribeOn(Schedulers.io()).doOnDispose {
                logger?.takeIf { it.logType().needToShowCommands() }
                    ?.log(this.state.javaClass.simpleName, "elm dispose cmd:$cmd")
            }
        })

        disposables.add(switchDisposable)
    }

    private fun update(msg: Msg, component: Component<S>, logger: RxElmLogger?): Pair<S, Cmd> {
        logger?.takeIf { it.logType().needToShowUpdates() }
            ?.log(this.state.javaClass.simpleName, "update with msg:${msg.javaClass.simpleName} ")

        val updateResult = component.update(msg, this.state)

        if (messageQueue.size > 0) {
            messageQueue.removeFirst()
        }

        return updateResult
    }

    private fun handleResponse(observable: Observable<Msg>): Disposable {
        return observable
            .observeOn(outputScheduler)
            .subscribe { msg ->
                if (msg !is Idle) {
                    messageQueue.addLast(msg)
                }

                pickNextMessageFromQueue()
            }
    }

    private fun cmdCall(cmd: Cmd): Observable<Msg> {
        return if (handleCmdErrors) {
            component.call(cmd)
                .onErrorResumeNext { err ->
                    logger?.takeIf { it.logType().needToShowCommands() }?.error(this.state.javaClass.simpleName, err)
                    Single.just(ErrorMsg(err, cmd))
                }
                .toObservable()
        } else {
            component.call(cmd)
                .toObservable()
        }
    }


    private fun pickNextMessageFromQueue() {
        logger?.takeIf { logger.logType() == LogType.All }?.let {
            logger.log(
                this.state.javaClass.simpleName,
                "pickNextMessageFromQueue, queue size:${messageQueue.size}"
            )
        }
        if (!lock && messageQueue.size > 0) {
            lock = true
            messageRelay.accept(messageQueue.first)
        }
    }

    fun render() {
        if (component is RenderableComponent) {
            component.render(this.state)
        }
    }

    fun accept(msg: Msg) {
        logger?.takeIf { logger.logType() == LogType.All }?.log(
            this.state.javaClass.simpleName,
            "accept msg: ${msg.javaClass.simpleName}, queue size:${messageQueue.size} lock:$lock "
        )
        messageQueue.addLast(msg)
        if (!lock && messageQueue.size == 1) {
            lock = true
            messageRelay.accept(messageQueue.first)
        }
    }

    fun getState(): S? {
        return if (this::state.isInitialized) {
            state
        } else null
    }

    fun addEventObservable(eventSource: Observable<Msg>): Disposable {
        return eventSource.subscribe { msg -> accept(msg) }
    }

    fun stop() {
        if (!disposables.isDisposed) {
            disposables.dispose()
        }
        commandsDisposablesMap.values.forEach {
            it.values.forEach { disposable ->
                if (!disposable.isDisposed) {
                    disposable.dispose()
                }
            }
        }
        rxElmSubscriptions?.dispose()
    }

}