extends SceneTree
## Знімок моделей солдата і гвинтівки крупним планом:
## godot --path . --rendering-driver opengl3 -s res://tests/ModelShot.gd -- /тека

var frame := 0
var out := "user://"
var soldiers: Array = []
var cam: Camera3D


func _initialize() -> void:
	pass


func _setup() -> void:
	var args := OS.get_cmdline_user_args()
	if args.size() > 0:
		out = args[0]
	var w := Node3D.new()
	root.add_child(w)
	var env := Environment.new()
	env.background_mode = Environment.BG_COLOR
	env.background_color = Color(0.55, 0.6, 0.65)
	env.ambient_light_color = Color(0.7, 0.7, 0.75)
	env.ambient_light_energy = 0.6
	var we := WorldEnvironment.new()
	we.environment = env
	w.add_child(we)
	var sun := DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-35, 40, 0)
	w.add_child(sun)
	var ground := MeshInstance3D.new()
	var pm := PlaneMesh.new()
	pm.size = Vector2(20, 20)
	ground.mesh = pm
	w.add_child(ground)
	for i in 3:
		var s = load("res://scripts/enemies/SoldierModel.gd").new()
		w.add_child(s)
		s.position = Vector3(-1.2 + i * 1.2, 0, 0)
		s.rotation.y = [PI + 0.4, PI * 0.5 + 0.3, PI - 0.5][i]
		soldiers.append(s)
	var M = load("res://scripts/level/Mats.gd")
	var rifle = load("res://scripts/weapons/RifleMesh.gd").build(M.get_mat("metal", Color(1.7, 1.7, 1.7), false), M.get_mat("metal", Color.WHITE, false), M.get_mat("metal", Color(1.9, 1.8, 1.4), false))
	w.add_child(rifle)
	rifle.position = Vector3(0, 0.3, 1.6)
	rifle.rotation_degrees = Vector3(0, 90, 0)
	cam = Camera3D.new()
	w.add_child(cam)
	cam.position = Vector3(0, 1.3, 2.9)
	cam.look_at(Vector3(0, 1.0, 0))
	cam.current = true


func _process(d: float) -> bool:
	frame += 1
	if frame == 2:
		_setup()
	if frame < 3:
		return false
	soldiers[0].animate(d, 0.0, true)
	soldiers[1].animate(d, 4.4, false)
	soldiers[2].animate(d, 0.0, false)
	if frame == 60:
		root.get_texture().get_image().save_jpg(out + "/model.jpg", 0.9)
		print("saved")
		quit()
	return false
