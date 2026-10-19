extends Node
## Процедурні звуки (генеруються в коді, без сторонніх ассетів). Autoload "Sfx".

const RATE := 22050
const MAX_VOICES := 24

var streams := {}
var _voices := 0
var _ambience: AudioStreamPlayer


func _ready() -> void:
	process_mode = Node.PROCESS_MODE_ALWAYS
	streams["shot"] = _wav(_shot(0.55, 1.0))
	streams["enemy_shot"] = _wav(_shot(0.3, 0.8))
	streams["explosion"] = _wav(_boom(1.6, 0.09, 2.6, 45.0))
	streams["far_boom"] = _wav(_boom(2.6, 0.025, 1.4, 32.0))
	streams["reload"] = _wav(_clicks())
	streams["hit"] = _wav(_tick(1700.0))
	streams["empty"] = _wav(_tick(900.0))
	streams["hum"] = _wav(_hum(), true)
	streams["drone"] = _wav(_buzz(), true)


# ---------- Відтворення ----------

func play(sname: String, pos: Variant = null, volume_db := 0.0, pitch_var := 0.08) -> void:
	var stream: AudioStream = streams.get(sname)
	if stream == null or _voices >= MAX_VOICES:
		return
	var scene := get_tree().current_scene
	var player: Node
	if pos is Vector3 and scene is Node3D:
		var p3 := AudioStreamPlayer3D.new()
		p3.stream = stream
		p3.unit_size = 12.0
		p3.max_distance = 250.0
		p3.volume_db = volume_db
		p3.pitch_scale = 1.0 + randf_range(-pitch_var, pitch_var)
		scene.add_child(p3)
		p3.global_position = pos
		p3.play()
		player = p3
	else:
		var p := AudioStreamPlayer.new()
		p.stream = stream
		p.volume_db = volume_db
		p.pitch_scale = 1.0 + randf_range(-pitch_var, pitch_var)
		(scene if scene else get_tree().root).add_child(p)
		p.play()
		player = p
	_voices += 1
	player.connect("finished", _on_voice_done.bind(player))


func _on_voice_done(p: Node) -> void:
	_voices -= 1
	p.queue_free()


func start_ambience() -> void:
	if _ambience == null:
		_ambience = AudioStreamPlayer.new()
		_ambience.stream = streams["hum"]
		_ambience.volume_db = -10.0
		add_child(_ambience)
	_ambience.play()


func stop_ambience() -> void:
	if _ambience:
		_ambience.stop()


# ---------- Генерація ----------

func _wav(s: PackedFloat32Array, loop := false) -> AudioStreamWAV:
	var data := PackedByteArray()
	data.resize(s.size() * 2)
	for i in s.size():
		data.encode_s16(i * 2, int(clampf(s[i], -1.0, 1.0) * 32767.0))
	var w := AudioStreamWAV.new()
	w.format = AudioStreamWAV.FORMAT_16_BITS
	w.mix_rate = RATE
	w.stereo = false
	w.data = data
	if loop:
		w.loop_mode = AudioStreamWAV.LOOP_FORWARD
		w.loop_begin = 0
		w.loop_end = s.size()
	return w


func _normalize(s: PackedFloat32Array, peak: float) -> PackedFloat32Array:
	var m := 0.0001
	for v in s:
		m = maxf(m, absf(v))
	for i in s.size():
		s[i] = s[i] / m * peak
	return s


func _shot(lp: float, peak: float) -> PackedFloat32Array:
	var n := int(0.4 * RATE)
	var s := PackedFloat32Array()
	s.resize(n)
	var y := 0.0
	for i in n:
		var t := float(i) / RATE
		var x := randf_range(-1.0, 1.0)
		y += (x - y) * lp
		var crack := x * exp(-t * 300.0)
		var thump := sin(TAU * 70.0 * t) * exp(-t * 22.0) * 0.9
		s[i] = y * exp(-t * 16.0) + crack * 0.6 + thump
	return _normalize(s, peak)


func _boom(dur: float, lp: float, decay: float, freq: float) -> PackedFloat32Array:
	var n := int(dur * RATE)
	var s := PackedFloat32Array()
	s.resize(n)
	var y := 0.0
	var y2 := 0.0
	for i in n:
		var t := float(i) / RATE
		y += (randf_range(-1.0, 1.0) - y) * lp
		y2 += (y - y2) * lp * 2.0
		var attack := minf(1.0, t * 200.0)
		s[i] = (y2 * 6.0 + sin(TAU * freq * t) * exp(-t * 4.0) * 0.6) * exp(-t * decay) * attack
	return _normalize(s, 0.95)


func _clicks() -> PackedFloat32Array:
	var n := int(0.45 * RATE)
	var s := PackedFloat32Array()
	s.resize(n)
	for i in n:
		var t := float(i) / RATE
		var v := 0.0
		for c in [0.0, 0.22, 0.3]:
			if t >= c:
				v += randf_range(-1.0, 1.0) * exp(-(t - c) * 180.0)
		s[i] = v
	return _normalize(s, 0.6)


func _tick(freq: float) -> PackedFloat32Array:
	var n := int(0.06 * RATE)
	var s := PackedFloat32Array()
	s.resize(n)
	for i in n:
		var t := float(i) / RATE
		s[i] = sin(TAU * freq * t) * exp(-t * 70.0)
	return _normalize(s, 0.5)


func _hum() -> PackedFloat32Array:
	var n := 6 * RATE
	var s := PackedFloat32Array()
	s.resize(n)
	var y := 0.0
	for i in n:
		var t := float(i) / RATE
		y += (randf_range(-1.0, 1.0) - y) * 0.01
		s[i] = y * 8.0 + sin(TAU * 41.0 * t) * 0.15 + sin(TAU * 62.0 * t) * 0.08 * (0.6 + 0.4 * sin(TAU * 0.5 * t))
	# плавне з'єднання петлі
	var fade := int(0.3 * RATE)
	for i in fade:
		var k := float(i) / fade
		s[i] = s[i] * k + s[n - fade + i] * (1.0 - k)
	return _normalize(s, 0.5)


func _buzz() -> PackedFloat32Array:
	var n := RATE
	var s := PackedFloat32Array()
	s.resize(n)
	for i in n:
		var t := float(i) / RATE
		var saw1 := fmod(t * 190.0, 1.0) * 2.0 - 1.0
		var saw2 := fmod(t * 194.0, 1.0) * 2.0 - 1.0
		s[i] = (saw1 + saw2) * 0.5
	return _normalize(s, 0.35)
