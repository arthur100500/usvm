import java.io.File

enum class ProblemType {
    EXCEPTION,
    WARNING,
    ASSERT
}

data class Problem(
    val type: ProblemType,
    val line: Int,
    val description: String,
    val stateId: Int,
    val path: String?
)

data class ForkPoint(
    val methodName: String,
    val line: Int,
    val description: String,
    var wasKilled: Boolean
)

private val DIGITS = "\\d+".toRegex()
private val STACK_DEPTH_PREFIX = "<\\|\\d+\\|>".toRegex()

fun removeStackDepth(line: String): String {
    return line.replace(STACK_DEPTH_PREFIX, "")
}

fun forkPointToString(forkPoint: ForkPoint): String {
    val killedText = if (forkPoint.wasKilled) "killed" else ""
    return " $killedText ${forkPoint.line}: ${forkPoint.methodName}\n"
}

fun problemToString(problem: Problem): String {
    return "-----------\nType: ${problem.type.name}\nIn log line: ${problem.line}\nHappened in state ${problem.stateId} with path ${problem.path}\nLine content: ${problem.description}\n"
}

fun analyzeLog() {
    val log = File(System.getenv("usvm.log") ?: "springLog.ansi")
    val summary = File(System.getenv("usvm.errors") ?: "springErrors.ansi")
    val statePaths = HashMap<Int, String?>()
    val foundProblems = ArrayList<Problem>()
    val forkPoints = ArrayList<ForkPoint>()
    var currentState = 0
    var lineNumber = 0
    var beforePrintPath = false

    log.forEachLine {
        lineNumber++
        val line = removeStackDepth(it)

        if (line.startsWith("picked state: ")) {
            currentState = DIGITS.find(it)!!.value.toInt()
        }

        if (line.startsWith("\u001B[34mForked on method ")) {
            forkPoints.add(ForkPoint(
                methodName = line.substring(22),
                line = lineNumber,
                description = line,
                wasKilled = false
            ))
        }

        if (line.startsWith("removed state: ")) {
            // Stacktrace should be greater than 50 really
            val latestForkPoint = forkPoints.lastOrNull()
            if (latestForkPoint != null && latestForkPoint.line - lineNumber < 50) {
                latestForkPoint.wasKilled = true
            }
        }

        val path: String? = statePaths[currentState]

        if (line.startsWith("exception thrown")) {
            foundProblems.add(Problem(ProblemType.EXCEPTION, lineNumber, line, currentState, path))
        } else if (line.contains("Assert failed: ")) {
            foundProblems.add(Problem(ProblemType.ASSERT, lineNumber, line, currentState, path))
        } else if (line.contains("[Warning!]")) {
            foundProblems.add(Problem(ProblemType.WARNING, lineNumber, line, currentState, path))
        } else if (line.contains("\u001B[36m[USVM] starting to analyze path")) {
            beforePrintPath = true
        } else if (line.startsWith("\u001B[36m") && beforePrintPath) {
            statePaths[currentState] = line
            beforePrintPath = false
        } else if (line.startsWith("\u001B[34m[")) {
            val digits = DIGITS.findAll(line.substring(5)).toList()
            val from = digits[0].value.toInt()
            val to = digits[2].value.toInt()
            statePaths[to] = statePaths[from]
        }
    }

    if (foundProblems.isEmpty()) {
        println("[Analyzer] No problems found during execution\n")
    } else {
        println("[Analyzer] Some problems while execution occurred (${foundProblems.size})\n")
    }

    summary.writeText("Analyzer report\n")
    foundProblems.groupBy { it.type }.forEach { t ->
        summary.appendText("Problems of type ${t.key}\n")
        t.value.forEach { p -> summary.appendText(problemToString(p)) }
    }

    summary.appendText("\n\nFork points:\n")
    forkPoints.forEach { summary.appendText(forkPointToString(it)) }
}
