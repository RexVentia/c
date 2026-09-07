package com.robot.simulator.ui.navigation

enum class Destination(
    val route: String,
    val title: String,
    val shortLabel: String,
) {
    Routes("routes", "路线库", "路线"),
    Editor("editor", "路线编辑器", "编辑"),
    Setup("setup", "仿真参数", "参数"),
    Running("running", "仿真运行", "运行"),
    History("history", "运行历史", "历史"),
}
