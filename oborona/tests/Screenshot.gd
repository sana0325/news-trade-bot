extends SceneTree
## Знімки екрана для перевірки графіки:
## godot --path . --rendering-driver opengl3 -s res://tests/Screenshot.gd -- <тека>

var frame := 0
var out := "user://"
var shots := [
	[60, Vector3(0, 0.1, 56), 0.0, -0.05],
	[140, Vector3(-3, 0.1, 30), 0.6, 0.0],
	[220, Vector3(4, 0.1, 0), -0.9, 0.1],
	[300, Vector3(0, 0.1, -10), PI, -0.1],
]


func _initialize() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() > 0:
		out = args[0]
	change_scene_to_file("res://scenes/Game.tscn")


func _process(_d: float) -> bool:
	frame += 1
	var g = current_scene
	if g == null or not g.has_method("_pause"):
		return false
	g.waves.active = false
	g.player.health = 100.0
	if frame == 10:
		for k in 3:
			var e = load("res://scripts/enemies/Enemy.gd").new()
			e.player = g.player
			e.level = g.level
			e.position = Vector3(-2.0 + k * 2.2, 0.1, 22.0 - k * 1.5)
			g.add_child(e)
	g.hud.visible = frame < 240
	for i in shots.size():
		var s: Array = shots[i]
		if frame == s[0] - 40:
			g.player.global_position = s[1]
			g.player.rotation.y = s[2]
			g.player.pitch = s[3]
		if frame == s[0]:
			var img := root.get_texture().get_image()
			img.save_jpg("%s/shot_%d.jpg" % [out, i], 0.85)
			print("saved ", i)
	if frame > 310:
		quit()
	return false
