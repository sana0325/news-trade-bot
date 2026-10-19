extends SceneTree
## Димовий тест без графіки:
## godot --headless --path . -s res://tests/SmokeTest.gd

var frame := 0
var game: Node
var fails := 0
var hits_taken := 0


func _initialize() -> void:
	change_scene_to_file("res://scenes/Game.tscn")


func check(cond: bool, msg: String) -> void:
	print(("[OK]   " if cond else "[FAIL] ") + msg)
	if not cond:
		fails += 1


func _process(_delta: float) -> bool:
	frame += 1
	game = current_scene
	if game == null or not game.has_method("_pause"):
		return false
	var p = game.player
	match frame:
		5:
			check(p != null and p.is_inside_tree(), "гравець створений")
			p.damaged.connect(func(_from): hits_taken += 1)
			check(game.level.cover_points.size() > 10, "точки укриття: %d" % game.level.cover_points.size())
			var nm = game.level.nav_region.navigation_mesh
			check(nm.get_polygon_count() > 0, "навмеш запечено: %d полігонів" % nm.get_polygon_count())
		400:
			var enemies := get_nodes_in_group("enemies")
			check(enemies.size() > 0, "вороги з'явились: %d" % enemies.size())
			var moved := false
			for e in enemies:
				if e.velocity.length() > 0.1 or e.state != 0:
					moved = true
			check(moved, "вороги рухаються/реагують")
			# стрільба: дивимося на першого ворога
			var e0 = enemies[0]
			var to: Vector3 = e0.global_position + Vector3(0, 1.66, 0) - p.head.global_position
			p.rotation.y = atan2(-to.x, -to.z)
			p.pitch = atan2(to.y, Vector2(to.x, to.z).length())
			p._on_pressed("fire")
		430:
			p._on_released("fire")
			check(p.rifle.mag < Config.RIFLE_MAG_SIZE, "постріли зроблено, у магазині %d" % p.rifle.mag)
			p._on_pressed("reload")
			p._on_pressed("grenade")
		440:
			p._on_released("grenade")
			check(p.grenades == Config.GRENADE_COUNT - 1, "граната кинута")
		460:
			check(p.rifle.reloading or p.rifle.mag == Config.RIFLE_MAG_SIZE, "перезарядка")
		600:
			p._on_pressed("drone")
			check(p.drone != null, "дрон запущено")
		640:
			p._on_pressed("fire")
		645:
			check(p.drone == null and p.drone_cooldown > 0.0, "дрон підірвано, кулдаун")
			p._on_released("fire")
		700:
			p.global_position = Vector3(0, 0.2, 20)
		800, 1600, 2400:
			for e in get_nodes_in_group("enemies"):
				print("  ворог ", e.global_position.snapped(Vector3.ONE*0.1), " ціль ", e.patrol_target.snapped(Vector3.ONE), " стан ", e.state, " шлях ", e.agent.get_current_navigation_path().size(), " next ", e.agent.get_next_path_position().snapped(Vector3.ONE*0.1), " fin ", e.agent.is_navigation_finished(), " v ", e.get_real_velocity().snapped(Vector3.ONE*0.1), " lk ", e.last_known.snapped(Vector3.ONE))
			if frame != 2400:
				return false
			var states := {}
			for e in get_nodes_in_group("enemies"):
				states[e.state] = states.get(e.state, 0) + 1
			print("Стани ворогів (0 патруль,1 помітив,2 укриття,3 стрільба,4 пошук): ", states)
			print("Влучань по гравцю: %d" % hits_taken)
			check(hits_taken > 0 or game.waves.kills > 0, "бій відбувся (вороги стріляють)")
		3000:
			print("Здоров'я гравця: %.0f, вбито: %d, хвиля: %d" % [p.health, game.waves.kills, game.waves.wave + 1])
			check(true, "3000 кадрів без збоїв")
			print("ПОМИЛОК: %d" % fails)
			quit(1 if fails > 0 else 0)
	return false
