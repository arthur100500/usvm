package machine

data class JcSpringMachineOptions(
    val springTestGenerationMode: JcSpringTestGenerationMode = JcSpringTestGenerationMode.SpringBootTest,
    val springAnalysisMode: JcSpringAnalysisMode = JcSpringAnalysisMode.EdgeCases,
)
