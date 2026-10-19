extends Node
## Налаштування користувача (зберігаються у user://settings.cfg). Autoload "Settings".

signal changed

const PATH := "user://settings.cfg"
const QUALITY_NAMES := ["Низька", "Середня", "Висока"]

var look_sensitivity: float = Config.DEFAULT_LOOK_SENS
var button_scale: float = Config.DEFAULT_BUTTON_SCALE
var aim_assist := true
var vibration := true
var quality := 1  # 0 низька, 1 середня, 2 висока


func _ready() -> void:
	load_settings()
	apply_quality()


func load_settings() -> void:
	var cf := ConfigFile.new()
	if cf.load(PATH) != OK:
		return
	look_sensitivity = cf.get_value("input", "look_sensitivity", look_sensitivity)
	button_scale = cf.get_value("input", "button_scale", button_scale)
	aim_assist = cf.get_value("input", "aim_assist", aim_assist)
	vibration = cf.get_value("input", "vibration", vibration)
	quality = cf.get_value("video", "quality", quality)


func save_settings() -> void:
	var cf := ConfigFile.new()
	cf.set_value("input", "look_sensitivity", look_sensitivity)
	cf.set_value("input", "button_scale", button_scale)
	cf.set_value("input", "aim_assist", aim_assist)
	cf.set_value("input", "vibration", vibration)
	cf.set_value("video", "quality", quality)
	cf.save(PATH)


func apply_quality() -> void:
	var vp := get_viewport()
	match quality:
		0:
			vp.scaling_3d_scale = 0.67
			vp.msaa_3d = Viewport.MSAA_DISABLED
			vp.mesh_lod_threshold = 4.0
		1:
			vp.scaling_3d_scale = 0.8
			vp.msaa_3d = Viewport.MSAA_DISABLED
			vp.mesh_lod_threshold = 2.0
		_:
			vp.scaling_3d_scale = 1.0
			vp.msaa_3d = Viewport.MSAA_2X
			vp.mesh_lod_threshold = 1.0
	Engine.max_fps = 60
	Mats.apply_quality()
	changed.emit()


## Множник кількості частинок залежно від якості.
func particle_mult() -> float:
	return [0.35, 0.65, 1.0][clampi(quality, 0, 2)]


func vibrate(ms: int) -> void:
	if vibration and OS.has_feature("mobile"):
		Input.vibrate_handheld(ms)
