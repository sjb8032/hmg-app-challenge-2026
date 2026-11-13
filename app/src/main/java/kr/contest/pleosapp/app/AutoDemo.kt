package kr.contest.pleosapp.app

import kr.contest.pleosapp.core.CheckInAnswer
import kr.contest.pleosapp.core.CourseFlavor
import kr.contest.pleosapp.core.CourseOption
import kr.contest.pleosapp.core.DriveEngine
import kr.contest.pleosapp.core.DriveMode
import kr.contest.pleosapp.core.DriveState
import kr.contest.pleosapp.core.DriveStep
import kr.contest.pleosapp.core.DriveText
import kr.contest.pleosapp.core.MINUTE_MS
import kr.contest.pleosapp.core.Mood
import kr.contest.pleosapp.core.OnTimeStyle
import kr.contest.pleosapp.core.Phase
import kr.contest.pleosapp.core.PickupNoticeKind
import kr.contest.pleosapp.core.Place
import kr.contest.pleosapp.core.PlaceKind
import kr.contest.pleosapp.core.Prompt
import kr.contest.pleosapp.core.RestKind
import kr.contest.pleosapp.core.RestMateState
import kr.contest.pleosapp.core.RestMateText

/*
 * 자동 시연: 버튼 하나로 시연 장면을 처음부터 끝까지 스스로 진행하고, 장면마다 지금 무슨 상황인지 화면 위에 해설을 띄운다.
 * 시연 영상을 찍을 때 누르는 사람 없이도 같은 흐름이 나오게 한다.
 *
 * 장면 하나 = [짚기] → 누르기 → 해설 → (조건이 되거나 시간이 다 되면) 다음 장면.
 * - 짚기: 화면의 버튼을 1.6초 동안 테두리로 깜빡여, 무엇을 누르는지 보는 사람이 알게 한다.
 * - 누르기: 화면에서 누르는 것과 같은 동작을 부른다 ([AutoDemoHost]).
 * - 해설: 누른 뒤의 화면 상태로 만든다 (실제로 나온 코스·시각·쉼터 이름을 그대로 쓴다).
 * - 넘어가기: 해설을 읽을 시간이 지나고 장면의 조건(예: 경로가 바뀜)이 되면. 조건이 끝내 안 돼도 정한 시간이 지나면 넘어가서 멈추지 않는다.
 *
 * 안드로이드에 의존하지 않는다 (시험에서 실제 엔진으로 끝까지 돌려 본다).
 */

/** 자동 시연이 보는 화면 상태 (컨트롤러의 [UiState]에서 필요한 것만) */
data class AutoView(
    val now: Long,
    val core: RestMateState,
    val drive: DriveState,
    val tripId: String?,
    val driving: Boolean,
    val jammed: Boolean,
    val clockHeld: Boolean,
)

/** 자동 시연이 누르는 것. 컨트롤러가 구현한다 (화면에서 누르는 것과 같은 동작). */
interface AutoDemoHost {
    fun view(): AutoView
    fun selectDemoTrip(id: String)
    fun demoDrive()
    fun demoTraffic()
    fun demoSkip(minutes: Int)
    fun demoSkipRest()
    fun openMode(mode: DriveMode)
    fun setDriveDestination(place: Place)
    fun setTarget(at: Long)
    fun setExtraMinutes(minutes: Int)
    fun setMood(mood: Mood)
    fun planCourses()
    fun chooseCourse(option: CourseOption)
    fun addDriveTime(minutes: Int)
    fun setOnTimeStyle(style: OnTimeStyle)
    fun setPickup(on: Boolean)
    fun circleForSpot()
    fun demoPark()
    fun pressDrowsy()
    fun answerCheckIn(answer: CheckInAnswer)
    fun stopAlarm()
    fun departNow()
}

/** 자동 시연의 이야기 하나 (기획서의 네 사용 시나리오와 같은 차례) */
enum class AutoChapter(val label: String, val title: String) {
    ON_TIME("온타임", "온타임 모드 · 저녁 약속에 맞춰"),
    PICKUP("픽업", "온타임 모드 · 학원 픽업 알림"),
    LONGER("롱웨이", "롱웨이 모드 · 퇴근길 조금 더"),
    REST("레스트", "레스트 모드 · 졸릴 때 쉬어 가기"),
}

/** 누르기 전에 짚어 보여 주는 곳 */
enum class AutoCue {
    /** 처음 화면의 온타임 모드 카드 */
    MODE_ON_TIME,

    /** 처음 화면의 롱웨이 모드 카드 */
    MODE_LONGER,

    /** 설정 화면의 '코스 찾기' */
    PLAN,

    /** 코스 후보 1번의 '이 코스로' */
    FIRST_COURSE,

    /** 달리는 중 '+15분 더' */
    ADD_TIME,

    /** 설정 화면의 픽업 알림 켜기 */
    PICKUP,

    /** 도착 화면의 '자리 없음' */
    NO_SPOT,

    /** 화면 아래 큰 버튼 (레스트 모드·알람 끄기·출발할게요) */
    BOTTOM,
}

/** 화면에 띄울 자동 시연 상태 */
data class AutoStatus(
    val chapter: AutoChapter?,
    /** 몇 번째 장면인지 (1부터) */
    val scene: Int,
    val total: Int,
    val title: String,
    val text: String,
    /** 지금 짚고 있는 곳 (짚는 동안만) */
    val cue: AutoCue? = null,
    /** 짚는 동안 띄울 말: "'코스 찾기' 누르기" */
    val cueLabel: String? = null,
    /** 시연에서만 하는 조작이면 그 이름: "시연 조작 · 막힘 +6분" */
    val tag: String? = null,
)

class AutoScene(
    val chapter: AutoChapter?,
    val title: String,
    /** 해설. 누른 뒤의 화면 상태로 만든다 */
    val text: (AutoView) -> String,
    /** 누를 것 (짚기가 있으면 짚은 뒤에) */
    val act: (AutoDemoHost) -> Unit = {},
    val cue: AutoCue? = null,
    val cueLabel: String? = null,
    /** 짚는 동안 해설 자리에 띄울 말 */
    val lead: String? = null,
    val tag: String? = null,
    /** 해설을 읽을 시간 (실제 시간) */
    val readMs: Long = AutoDemoScript.READ_MS,
    /** 이 조건이 되어야 다음 장면으로 (장면을 시작할 때의 상태와 지금 상태) */
    val until: (start: AutoView, now: AutoView) -> Boolean = { _, _ -> true },
    /** 조건이 끝내 안 돼도 넘어가는 시간 (실제 시간) */
    val timeoutMs: Long = AutoDemoScript.TIMEOUT_MS,
    /** 조건을 기다리는 동안 이 간격으로 [repeat]을 되풀이한다 (빨리 감기) */
    val everyMs: Long? = null,
    val repeat: (AutoDemoHost) -> Unit = {},
)

/** 장면을 차례로 진행한다. [tick]을 0.2초쯤마다 부른다. */
class AutoDemoRunner(private val host: AutoDemoHost) {
    private var scenes: List<AutoScene> = emptyList()
    private var index = -1
    private var enteredAt = 0L
    private var actedAt = 0L
    private var acted = false
    private var lastRepeatAt = 0L
    private var start: AutoView? = null
    private var promptSince: Long? = null

    var status: AutoStatus? = null
        private set

    val running: Boolean get() = index in scenes.indices

    /** 조건을 못 채우고 시간이 다 되어 넘어간 장면 (시험에서 본다) */
    val timedOut = ArrayList<String>()

    /** 지나간 해설 (시험에서 본다) */
    val transcript = ArrayList<String>()

    /** @param chapter null이면 세 이야기를 모두 (처음과 끝 인사 포함) */
    fun start(chapter: AutoChapter?, realNow: Long) {
        scenes = AutoDemoScript.scenes(chapter)
        timedOut.clear()
        transcript.clear()
        enter(0, realNow)
    }

    fun stop() {
        scenes = emptyList()
        index = -1
        status = null
        start = null
    }

    /** 다음 장면으로 바로 */
    fun skip(realNow: Long) {
        if (!running) return
        if (!acted) act(scenes[index], realNow)
        advance(realNow)
    }

    fun tick(realNow: Long) {
        if (!running) return
        val scene = scenes[index]
        if (!acted) {
            if (realNow - enteredAt >= CUE_MS) act(scene, realNow)
            return
        }
        val now = host.view()
        answerCheckIn(now, realNow)
        val began = start ?: now
        val done = scene.until(began, now)
        val every = scene.everyMs
        if (every != null && !done && realNow - lastRepeatAt >= every) {
            lastRepeatAt = realNow
            scene.repeat(host)
        }
        val elapsed = realNow - actedAt
        if (elapsed >= scene.timeoutMs && !done) {
            timedOut += scene.title
            advance(realNow)
        } else if (elapsed >= scene.readMs && done) {
            advance(realNow)
        }
    }

    private fun advance(realNow: Long) {
        if (index >= scenes.lastIndex) stop() else enter(index + 1, realNow)
    }

    private fun enter(i: Int, realNow: Long) {
        index = i
        enteredAt = realNow
        acted = false
        promptSince = null
        val scene = scenes[i]
        status = AutoStatus(
            chapter = scene.chapter,
            scene = i + 1,
            total = scenes.size,
            title = scene.title,
            text = scene.lead.orEmpty(),
            cue = scene.cue,
            cueLabel = scene.cueLabel,
            tag = scene.tag,
        )
        if (scene.cue == null) act(scene, realNow)
    }

    private fun act(scene: AutoScene, realNow: Long) {
        start = host.view()
        scene.act(host)
        acted = true
        actedAt = realNow
        lastRepeatAt = realNow
        val text = scene.text(host.view())
        transcript += "${scene.title} — $text"
        status = status?.copy(text = text, cue = null, cueLabel = null)
    }

    /** 쉼터로 가는 동안 '괜찮으세요?'가 뜨면 잠깐 보여 준 뒤 '괜찮아요'로 답한다 (대본에 없는 물음에 멈추지 않게) */
    private fun answerCheckIn(now: AutoView, realNow: Long) {
        if (now.core.prompt != Prompt.CheckIn) {
            promptSince = null
            return
        }
        val since = promptSince ?: realNow.also { promptSince = it }
        if (realNow - since >= CHECK_IN_ANSWER_MS) {
            promptSince = null
            host.answerCheckIn(CheckInAnswer.OK)
        }
    }

    companion object {
        /** 누르기 전에 짚어 보여 주는 시간 */
        const val CUE_MS = 1_600L
        private const val CHECK_IN_ANSWER_MS = 2_000L
    }
}

/** 자동 시연 대본 */
object AutoDemoScript {
    const val READ_MS = 7_000L
    const val TIMEOUT_MS = 30_000L

    /** 고속도로 이야기에서 졸리기 전에 달려 둘 시간 (가상) */
    private const val HIGHWAY_DRIVEN_MS = 50 * MINUTE_MS

    fun scenes(chapter: AutoChapter?): List<AutoScene> = when (chapter) {
        null -> listOf(intro) + onTime + pickup + longer + rest + listOf(outro)
        AutoChapter.ON_TIME -> onTime + listOf(outro)
        AutoChapter.PICKUP -> pickup + listOf(outro)
        AutoChapter.LONGER -> longer + listOf(outro)
        AutoChapter.REST -> rest + listOf(outro)
    }

    private val intro = AutoScene(
        chapter = null,
        title = "드라이브메이트 자동 시연",
        text = {
            "약속 시각에 맞춰 가는 온타임(학원 픽업 알림까지), 조금 더 달리는 롱웨이, 졸릴 때 쉬어 가는 레스트를 " +
                "차례로 보여 드려요. 위치·속도·시각은 시연용 모의 값이에요."
        },
        readMs = 7_500L,
    )

    private val outro = AutoScene(
        chapter = null,
        title = "자동 시연을 마쳤어요",
        text = {
            "시연에서는 모의 내비와 빨리 가는 가상 시계로 움직였어요. " +
                "실제 차에서는 Pleos 차량·내비 신호를 받아 똑같이 안내해요."
        },
        readMs = 7_000L,
    )

    // ---------------------------------------------------------------- 온타임 모드: 시내 저녁 약속

    private val onTime = listOf(
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "약속까지 시간이 남아요",
            act = { it.selectDemoTrip("city") },
            text = { v ->
                val remain = v.drive.naviRemainingMs
                val target = onTimeTarget(v)
                buildString {
                    append("퇴근길 저녁 ${spoken(v.now)}, 논현역이에요. ")
                    if (target != null) append("광화문 약속은 ${spoken(target)}. ")
                    if (remain != null) {
                        append("바로 가면 ${DriveText.duration(remain)} 걸려 ${spoken(v.now + remain)}에 닿으니, ")
                        append("가서 한참 기다려야 해요.")
                    }
                }
            },
            readMs = 7_500L,
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "온타임 모드 켜기",
            cue = AutoCue.MODE_ON_TIME,
            cueLabel = "'온타임 모드' 누르기",
            lead = "약속·픽업 시각에 딱 맞춰 도착하게 길을 짜 주는 모드예요.",
            act = { h ->
                h.openMode(DriveMode.ON_TIME)
                onTimeTarget(h.view())?.let(h::setTarget)
            },
            text = { v ->
                val d = v.drive
                val target = d.targetAt
                val direct = d.directMs
                buildString {
                    append("끝낼 곳은 내비에 걸린 ${d.destination?.name ?: "목적지"}")
                    if (target != null) append(", 도착 목표는 ${spoken(target)}")
                    append(". ")
                    if (target != null && direct != null) {
                        val spare = target - v.now - direct
                        if (spare > MINUTE_MS) append("바로 가면 ${DriveText.duration(spare)}이 남아요. 이 시간을 어떻게 쓸지 정해요.")
                    }
                }
            },
            readMs = 7_500L,
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "남는 시간을 채울 코스 찾기",
            cue = AutoCue.PLAN,
            cueLabel = "'코스 찾기' 누르기",
            lead = "남는 시간을 그냥 버리지 않고 쓸 길을 찾아요.",
            act = { it.planCourses() },
            text = { v ->
                val options = v.drive.options
                val names = options.mapNotNull { it.via.firstOrNull()?.name }
                if (names.isEmpty()) {
                    "코스 후보를 찾았어요. 지도 번호가 왼쪽 코스 카드와 같아요."
                } else {
                    "${names.joinToString(", ")}처럼 경치 좋은 곳을 거쳐, 목표보다 3분쯤 일찍 닿는 코스 ${options.size}개를 찾았어요. " +
                        "지도 번호가 왼쪽 코스 카드와 같아요."
                }
            },
            until = { _, v -> v.drive.step == DriveStep.CHOOSE },
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "코스 고르기",
            cue = AutoCue.FIRST_COURSE,
            cueLabel = "1번 코스 고르기",
            lead = "1번 코스로 갈게요.",
            act = { h -> h.view().drive.options.firstOrNull()?.let(h::chooseCourse) },
            text = { v ->
                val d = v.drive
                val via = d.chosen?.via?.firstOrNull()?.name
                buildString {
                    if (via != null) append("${DriveText.withObject(RestMateText.spokenName(via))} 거쳐 가요. ")
                    append("내비 경로에 경유지가 들어가고, 위 시간 막대에 지금·도착 예상·목표가 함께 보여요.")
                    d.arrivalEstimate?.let { append(" 도착 예상 ${spoken(it)}.") }
                }
            },
            until = { _, v -> v.drive.step == DriveStep.ACTIVE },
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "출발",
            tag = "시연 조작 · 출발 · 주행",
            act = { it.demoDrive() },
            text = {
                "출발했어요. 시연에서는 가상 시계가 12배로 흘러요. " +
                    "달리는 동안 도착 예상을 목표와 계속 견줘요."
            },
            readMs = 6_500L,
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "갑자기 길이 막혔어요",
            tag = "시연 조작 · 막힘 +6분",
            act = { it.demoTraffic() },
            text = {
                "도착 예상이 목표보다 늦어졌어요. 잠깐 막힌 것일 수도 있어서 바로 길을 바꾸지 않고, " +
                    "1분 30초 지켜본 뒤에도 늦으면 지금 자리에서 늦지 않는 코스로 바꿔요."
            },
            readMs = 8_000L,
            // 경유지를 바꾸거나 빼면 다음 장면
            until = { start, v -> rerouted(start.drive, v.drive) },
            timeoutMs = 25_000L,
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "늦지 않는 길로 바꿨어요",
            text = { v ->
                val d = v.drive
                buildString {
                    append(d.message ?: "경로를 바꿨어요.")
                    val eta = d.arrivalEstimate
                    val target = d.targetAt
                    if (eta != null && target != null) append(" 도착 예상 ${spoken(eta)}, 목표 ${spoken(target)} 안으로 다시 들어왔어요.")
                }
            },
            readMs = 7_500L,
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "도착까지 빨리 감기",
            tag = "시연 조작 · 시간 건너뛰기",
            text = { "남은 길은 빨리 감아 볼게요." },
            readMs = 2_500L,
            until = { _, v -> v.drive.step == DriveStep.ARRIVED },
            timeoutMs = 40_000L,
            everyMs = 800L,
            repeat = { it.demoSkip(3) },
        ),
        AutoScene(
            chapter = AutoChapter.ON_TIME,
            title = "약속 시각에 맞춰 도착",
            text = { v ->
                val d = v.drive
                buildString {
                    val at = d.arrivedAt
                    val target = d.targetAt
                    if (at != null && target != null) append("${spoken(at)} 도착, 목표 ${spoken(target)}. ")
                    d.message?.let { append("$it ") }
                    append("길이 막혔어도 코스를 바꿔 약속에 늦지 않았어요.")
                }
            },
            readMs = 7_500L,
        ),
    )

    // ---------------------------------------------------------------- 온타임 모드: 학원 픽업 알림

    private val pickup = listOf(
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "학원으로 아이를 데리러",
            act = { it.selectDemoTrip("city") },
            text = { v ->
                "다시 저녁 ${spoken(v.now)}, 논현역이에요. 이번엔 대치동 학원으로 아이를 데리러 가요. " +
                    "하원 시간의 학원 앞은 차가 몰려 세울 곳을 찾기 어려워요."
            },
            readMs = 7_000L,
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "온타임 모드로 픽업 시각 맞추기",
            cue = AutoCue.MODE_ON_TIME,
            cueLabel = "'온타임 모드' 누르기",
            lead = "픽업 시각에 맞춰 가요.",
            act = { h ->
                h.openMode(DriveMode.ON_TIME)
                academy(h.view())?.let(h::setDriveDestination)
                // 기획서 시나리오처럼 남는 시간은 돌면서 맞춘다 (즐겨찾기의 기본은 근처에서 기다리기)
                h.setOnTimeStyle(OnTimeStyle.LOOP)
                pickupTarget(h.view())?.let(h::setTarget)
            },
            text = { v ->
                val d = v.drive
                buildString {
                    append("끝낼 곳은 즐겨찾기 ${d.destination?.name ?: "아이 학원"}")
                    d.targetAt?.let { append(", 픽업은 ${spoken(it)}") }
                    append(". ")
                    val direct = d.directMs
                    val target = d.targetAt
                    if (direct != null && target != null) {
                        val early = target - v.now - direct
                        if (early > MINUTE_MS) append("바로 가면 ${DriveText.duration(early)} 일찍 닿아 학원 앞에서 기다려야 해요.")
                    }
                }
            },
            readMs = 7_500L,
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "픽업 알림 켜기",
            cue = AutoCue.PICKUP,
            cueLabel = "픽업 알림 켜기",
            lead = "도착 소식을 아이에게 문자로 알려요.",
            act = { it.setPickup(true) },
            text = { v ->
                val to = v.drive.pickupTo ?: "아이"
                "도착 3분 전에는 도착 시각과 만날 곳을, P단으로 세우면 세운 곳을, 세울 곳이 없으면 다시 오는 시각을 ${to}에게 문자로 보내요. " +
                    "시연에서는 실제로 보내지 않고 보낼 문자를 화면에 보여 줘요."
            },
            readMs = 8_500L,
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "남는 시간을 채울 코스 찾기",
            cue = AutoCue.PLAN,
            cueLabel = "'코스 찾기' 누르기",
            lead = "남는 시간만큼 돌아가는 길을 찾아요.",
            act = { it.planCourses() },
            text = { v ->
                val first = v.drive.options.firstOrNull()
                buildString {
                    append("코스 후보 ${v.drive.options.size}개를 찾았어요. ")
                    if (first != null) append("1번은 ${first.title}. ")
                    append("픽업 시각보다 3분쯤 일찍 닿게 맞춰요.")
                }
            },
            readMs = 6_500L,
            until = { _, v -> v.drive.step == DriveStep.CHOOSE },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "코스 고르기",
            cue = AutoCue.FIRST_COURSE,
            cueLabel = "1번 코스 고르기",
            lead = "1번 코스로 갈게요.",
            act = { h -> h.view().drive.options.firstOrNull()?.let(h::chooseCourse) },
            text = { v ->
                buildString {
                    append("내비 경로에 경유지가 들어갔어요. ")
                    v.drive.arrivalEstimate?.let { append("도착 예상 ${spoken(it)}. ") }
                    append("달리는 화면 왼쪽에 픽업 알림 카드가 생겨요.")
                }
            },
            readMs = 6_500L,
            until = { _, v -> v.drive.step == DriveStep.ACTIVE },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "학원 가까이까지",
            tag = "시연 조작 · 출발 · 시간 건너뛰기",
            act = { it.demoDrive() },
            text = { "출발했어요. 학원 가까이까지 빨리 감아 볼게요." },
            readMs = 3_500L,
            until = { _, v -> v.drive.pickupNotices.any { it.kind == PickupNoticeKind.APPROACH } },
            timeoutMs = 30_000L,
            everyMs = 800L,
            repeat = { it.demoSkip(1) },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "도착 3분 전 · 아이에게 문자",
            text = { v ->
                val n = v.drive.pickupNotices.lastOrNull { it.kind == PickupNoticeKind.APPROACH }
                if (n == null) {
                    "도착 3분 전에 아이에게 문자를 보내요."
                } else {
                    "${n.to}에게 \"${n.body}\" 문자를 보냈어요. 지금 차 위치도 지도 링크로 함께 보내요."
                }
            },
            readMs = 8_500L,
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "학원 앞 도착",
            tag = "시연 조작 · 시간 건너뛰기",
            text = { "학원 앞까지 가 볼게요." },
            readMs = 3_000L,
            until = { _, v -> v.drive.step == DriveStep.ARRIVED && !v.driving },
            timeoutMs = 25_000L,
            everyMs = 800L,
            repeat = { it.demoSkip(1) },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "세울 곳이 없을 때",
            cue = AutoCue.NO_SPOT,
            cueLabel = "'자리 없음' 누르기",
            lead = "학원 앞이 하원 차로 꽉 찼어요.",
            act = { it.circleForSpot() },
            text = { v ->
                val d = v.drive
                buildString {
                    append("주변을 한 바퀴 돌아 다시 오는 길로 바꿨어요")
                    d.arrivalEstimate?.let { append(" (${spoken(it)}쯤 다시 도착)") }
                    append(". ${d.pickupTo ?: "아이"}에게도 다시 오는 시각을 보냈어요. 도는 동안은 늦어도 길을 줄이지 않아요.")
                }
            },
            readMs = 8_500L,
            until = { _, v -> v.drive.circling },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "한 바퀴 돌고 다시 학원으로",
            tag = "시연 조작 · 시간 건너뛰기",
            text = { "한 바퀴 도는 길은 빨리 감아 볼게요." },
            readMs = 3_000L,
            until = { _, v -> v.drive.step == DriveStep.ARRIVED && !v.driving },
            timeoutMs = 30_000L,
            everyMs = 800L,
            repeat = { it.demoSkip(1) },
        ),
        AutoScene(
            chapter = AutoChapter.PICKUP,
            title = "빈 곳에 세우면 세운 곳을 다시",
            tag = "시연 조작 · P단 주차",
            act = { it.demoPark() },
            text = { v ->
                val n = v.drive.pickupNotices.lastOrNull { it.kind == PickupNoticeKind.PARKED }
                buildString {
                    append("이번엔 빈 곳에 P단으로 세웠어요. ")
                    if (n != null) append("세운 곳을 ${n.to}에게 지도 링크로 보냈어요. ")
                    append("아이는 헤매지 않고 차로 와요.")
                }
            },
            readMs = 8_000L,
        ),
    )

    // ---------------------------------------------------------------- 롱웨이 모드: 퇴근길 조금 더

    private val longer = listOf(
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "바로 집에 가기 아쉬운 퇴근길",
            act = { it.selectDemoTrip("city") },
            text = { v ->
                "다시 저녁 ${spoken(v.now)}, 논현역이에요. 이번엔 집으로 가는 길에 30분쯤 더 달리며 머리를 식히고 싶어요."
            },
            readMs = 6_500L,
        ),
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "롱웨이 모드 켜기",
            cue = AutoCue.MODE_LONGER,
            cueLabel = "'롱웨이 모드' 누르기",
            lead = "경치 좋은 길로 조금 더 달리다 도착하는 모드예요.",
            act = { h ->
                h.openMode(DriveMode.LONGER)
                h.view().drive.places.firstOrNull { it.kind == PlaceKind.HOME }?.let(h::setDriveDestination)
                h.setExtraMinutes(30)
            },
            text = { v ->
                val d = v.drive
                buildString {
                    append("끝낼 곳은 ${d.destination?.name ?: "집"}, 더 달릴 시간은 ${d.extraMinutes}분. ")
                    append(
                        when (d.flavor) {
                            CourseFlavor.NIGHT_VIEW -> "어두운 시간이라 야경 길을 먼저 찾아요."
                            CourseFlavor.RIVER -> "낮 데이트라 강변 길을 먼저 찾아요."
                            CourseFlavor.AVOID_CITY -> "낮에 혼자면 차 많은 도심을 피하는 길을 먼저 찾아요."
                            else -> "어떤 길이 좋은지 고를 수 있어요."
                        },
                    )
                }
            },
        ),
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "더 달릴 코스 찾기",
            cue = AutoCue.PLAN,
            cueLabel = "'코스 찾기' 누르기",
            lead = "시각과 분위기에 맞는 길을 찾아요.",
            act = { it.planCourses() },
            text = { v ->
                val names = v.drive.options.mapNotNull { it.via.firstOrNull()?.name }
                buildString {
                    if (names.isNotEmpty()) append("${names.joinToString(", ")} 코스가 나왔어요. ")
                    append("위의 칩으로 야경·강변·다리·공원·도심 피하기를 바꾸면 후보가 다시 나와요.")
                }
            },
            until = { _, v -> v.drive.step == DriveStep.CHOOSE },
        ),
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "코스 고르기",
            cue = AutoCue.FIRST_COURSE,
            cueLabel = "1번 코스 고르기",
            lead = "1번 코스로 갈게요.",
            act = { h -> h.view().drive.options.firstOrNull()?.let(h::chooseCourse) },
            text = { v ->
                val d = v.drive
                val via = d.chosen?.via?.firstOrNull()?.name
                buildString {
                    if (via != null) append("${DriveText.withObject(RestMateText.spokenName(via))} 거쳐 ")
                    d.targetAt?.let { append("${spoken(it)}쯤 집에 닿아요. ") }
                    append("롱웨이는 마감이 아니라 예정이라, 조금 늦어져도 재촉하지 않아요.")
                }
            },
            until = { _, v -> v.drive.step == DriveStep.ACTIVE },
        ),
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "출발",
            tag = "시연 조작 · 출발 · 주행",
            act = { it.demoDrive() },
            text = {
                "혼자 쉬는 분위기에서는 경로가 바뀔 때만 짧게 말하고, 데이트 분위기면 말없이 화면으로만 알려요. " +
                    "차가 연결되면 분위기에 맞춰 무드등과 공조도 바꿔요."
            },
            readMs = 7_000L,
        ),
        AutoScene(
            chapter = AutoChapter.LONGER,
            title = "15분 더 달리고 싶을 때",
            cue = AutoCue.ADD_TIME,
            cueLabel = "'+15분 더' 누르기",
            lead = "달리는 중에도 더 달릴 시간을 늘릴 수 있어요.",
            act = { it.addDriveTime(15) },
            text = { v ->
                val d = v.drive
                buildString {
                    append(d.message ?: "15분 더 달려요.")
                    d.targetAt?.let { append(" 집에는 ${spoken(it)}쯤 닿아요.") }
                }
            },
            readMs = 7_000L,
        ),
    )

    // ---------------------------------------------------------------- 레스트 모드: 고속도로에서 졸릴 때

    private val rest = listOf(
        AutoScene(
            chapter = AutoChapter.REST,
            title = "고속도로로 먼 길",
            act = { it.selectDemoTrip("highway") },
            text = { v ->
                "오전 ${spoken(v.now)}, 용인에서 강릉 숙소로 가는 영동고속도로예요. 내비에는 강릉 숙소가 걸려 있어요."
            },
            readMs = 6_500L,
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "쉬지 않고 달리는 중",
            tag = "시연 조작 · 출발 · 시간 건너뛰기",
            act = { it.demoDrive() },
            text = {
                "출발한 뒤 시간을 빨리 감아 50분쯤 쉬지 않고 달려요. 오른쪽 '연속 주행'이 늘어나요."
            },
            readMs = 4_500L,
            until = { start, v -> v.now - start.now >= HIGHWAY_DRIVEN_MS },
            timeoutMs = 20_000L,
            everyMs = 700L,
            repeat = { it.demoSkip(5) },
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "졸릴 때는 레스트 모드 한 번",
            cue = AutoCue.BOTTOM,
            cueLabel = "'레스트 모드' 누르기",
            lead = "어느 화면에서나 아래 큰 버튼 하나면 돼요.",
            act = { it.pressDrowsy() },
            text = { v ->
                val target = v.core.target
                if (target == null) {
                    "진행 방향에서 가장 빨리 닿는 쉼터로 안내해요."
                } else {
                    val name = RestMateText.spokenName(target.area.name)
                    "진행 방향에서 가장 빨리 닿는 ${name}(${RestMateText.distance(target.roadMeters)}, " +
                        "약 ${RestMateText.etaMinutes(target.etaMinutes)}분)로 안내해요. " +
                        "가던 목적지는 그대로 두고 쉼터를 경유지로 넣어요."
                }
            },
            readMs = 8_000L,
            until = { _, v -> v.core.phase != Phase.DRIVING },
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "쉼터에 닿으면 저절로 휴식",
            text = {
                "쉼터에 P단으로 세우면 바로 쉬기 시작해요. 차가 졸음을 알아채거나 2시간 넘게 달려도 이렇게 먼저 쉬어 가자고 해요."
            },
            readMs = 5_000L,
            until = { _, v -> v.core.phase == Phase.RESTING },
            timeoutMs = 30_000L,
            everyMs = 1_500L,
            repeat = { it.demoSkip(1) },
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "눈 붙이기",
            text = { v ->
                val rest = v.core.rest
                buildString {
                    val area = rest?.area?.name?.let(RestMateText::spokenName)
                    append(if (area != null) "${area}에서 쉬는 중이에요. " else "쉬는 중이에요. ")
                    append("무드등을 낮추고 공조를 쉬기 좋게 맞췄어요. ")
                    if (rest != null && rest.kind == RestKind.NAP) {
                        append("${rest.plannedMinutes}분 뒤 ${spoken(rest.alarmAt)}에 알람으로 깨워요.")
                    }
                }
            },
            readMs = 7_500L,
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "알람 시각",
            tag = "시연 조작 · 휴식 건너뛰기",
            act = { it.demoSkipRest() },
            text = {
                "알람이 울리면 무드등을 밝히고 시원한 바깥 바람을 넣어 잠을 깨워요."
            },
            readMs = 5_000L,
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "깬 뒤 몸 깨우기",
            cue = AutoCue.BOTTOM,
            cueLabel = "'알람 끄기' 누르기",
            lead = "알람을 꺼요.",
            act = { it.stopAlarm() },
            text = { v ->
                val wake = v.core.rest?.wakeMs ?: 0L
                if (wake > 0) {
                    "막 깨면 멍할 수 있어서 ${RestMateText.duration(wake)}쯤 몸을 깨운 뒤 출발하라고 알려요. " +
                        "깨우는 시간은 빨리 감아 볼게요."
                } else {
                    "준비되면 천천히 출발하라고 알려요."
                }
            },
            readMs = 7_000L,
            until = { _, v -> wakeDone(v.core) },
            timeoutMs = 20_000L,
            everyMs = 1_000L,
            repeat = { it.demoSkip(2) },
        ),
        AutoScene(
            chapter = AutoChapter.REST,
            title = "다시 출발",
            cue = AutoCue.BOTTOM,
            cueLabel = "'출발할게요' 누르기",
            lead = "다 깼으면 출발해요.",
            act = { it.departNow() },
            text = { v ->
                val dest = v.drive.naviDestination?.name ?: "목적지"
                "실내를 쉬기 전 설정으로 돌리고, 내비는 다시 ${DriveText.toward(dest)} 안내해요. " +
                    "깬 지 얼마 안 됐으니 처음 몇 분은 천천히 가라고 일러 줘요."
            },
            readMs = 7_500L,
        ),
    )

    // ---------------------------------------------------------------- 도우미

    /** 온타임 시연의 목표: 시각 칩 두 번째 (시내는 바로 가면 6:50 도착 → 7:10) */
    private fun onTimeTarget(v: AutoView): Long? {
        val direct = v.drive.directMs ?: v.drive.naviRemainingMs ?: return null
        return DriveEngine.targetChoices(v.now, direct).getOrNull(1)
    }

    /** 픽업 시연의 끝낼 곳: 내비 즐겨찾기의 '아이 학원' */
    private fun academy(v: AutoView): Place? =
        v.drive.places.firstOrNull { it.kind == PlaceKind.BOOKMARK }

    /** 픽업 시연의 목표: 시각 칩 첫 번째 (바로 가면 10분쯤 일찍 닿는 시각) */
    private fun pickupTarget(v: AutoView): Long? {
        val direct = v.drive.directMs ?: return null
        return DriveEngine.targetChoices(v.now, direct).firstOrNull()
    }

    /** 경유지를 바꾸거나 뺐는지 */
    private fun rerouted(before: DriveState, now: DriveState): Boolean =
        now.chosen?.id != before.chosen?.id || (now.viaCleared && !before.viaCleared)

    private fun wakeDone(s: RestMateState): Boolean {
        val rest = s.rest ?: return true
        val woke = rest.wakeStartedAt ?: return false
        return !rest.alarmRinging && s.now >= woke + rest.wakeMs
    }

    /** 해설용 시각: "6시 30분", "7시" */
    private fun spoken(ms: Long): String = DriveText.spokenClock(ms)
}
