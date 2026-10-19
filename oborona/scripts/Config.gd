extends Node
## Усі параметри балансу гри. Autoload "Config".

# --- Гравець ---
const PLAYER_MAX_HEALTH := 100.0
const PLAYER_WALK_SPEED := 5.0
const PLAYER_CROUCH_SPEED := 2.4
const PLAYER_ADS_SPEED_MULT := 0.6
const PLAYER_ACCEL := 30.0
const PLAYER_JUMP_VELOCITY := 4.6
const PLAYER_HEIGHT := 1.8
const PLAYER_CROUCH_HEIGHT := 1.1
const PLAYER_RADIUS := 0.35
const HEALTH_REGEN_DELAY := 5.0
const HEALTH_REGEN_RATE := 8.0
const GRAVITY := 9.8
const SELF_EXPLOSION_MULT := 0.5

# --- Штурмова гвинтівка ---
const RIFLE_MAG_SIZE := 30
const RIFLE_RESERVE_AMMO := 210
const RIFLE_DAMAGE := 25.0
const RIFLE_HEADSHOT_MULT := 2.0
const RIFLE_FIRE_RATE := 10.0          # пострілів/с
const RIFLE_RANGE := 150.0
const RIFLE_RELOAD_TIME := 2.0
const RIFLE_SPREAD_HIP := 2.2          # градуси
const RIFLE_SPREAD_ADS := 0.35
const RIFLE_SPREAD_MOVE_MULT := 1.6
const RIFLE_SPREAD_CROUCH_MULT := 0.7
const RIFLE_SPREAD_PER_SHOT := 0.35
const RIFLE_SPREAD_MAX := 5.0
const RIFLE_SPREAD_RECOVERY := 7.0     # град/с
const RIFLE_RECOIL_PITCH := 1.0        # градуси за постріл
const RIFLE_RECOIL_YAW := 0.4
const RIFLE_RECOIL_ADS_MULT := 0.55
const RIFLE_RECOIL_RECOVERY := 9.0
const FOV_HIP := 75.0
const FOV_ADS := 50.0
const ADS_LOOK_MULT := 0.55

# --- Гранати ---
const GRENADE_COUNT := 3
const GRENADE_THROW_SPEED := 15.0
const GRENADE_THROW_UP := 3.0
const GRENADE_FUSE := 2.5
const GRENADE_RADIUS := 7.0
const GRENADE_DAMAGE := 160.0

# --- FPV-дрон ---
const DRONE_DURATION := 15.0
const DRONE_SPEED := 13.0
const DRONE_SPEED_MIN := 5.0
const DRONE_SPEED_MAX := 22.0
const DRONE_STEER_MULT := 0.8
const DRONE_COOLDOWN := 45.0
const DRONE_RADIUS := 6.0
const DRONE_DAMAGE := 220.0

# --- Вороги ---
const ENEMY_HEALTH := 100.0
const ENEMY_WALK_SPEED := 2.2
const ENEMY_RUN_SPEED := 4.4
const ENEMY_VIEW_DISTANCE := 45.0
const ENEMY_VIEW_HALF_ANGLE := 65.0    # градуси
const ENEMY_REACTION_TIME := 0.7
const ENEMY_DAMAGE := 7.0
const ENEMY_BURST := 3
const ENEMY_BURST_DELAY := 0.13
const ENEMY_FIRE_INTERVAL := 1.3
const ENEMY_BASE_SPREAD := 9.0         # градуси на початку контакту
const ENEMY_MIN_SPREAD := 1.6          # мінімальний розкид після «пристрілки»
const ENEMY_SPREAD_DECAY := 1.1        # град/с, поки ворог бачить гравця
const ENEMY_RELOCATE_TIME := Vector2(7.0, 12.0)
const ENEMY_LOSE_SIGHT_TIME := 3.5
const ENEMY_COVER_SEARCH_RADIUS := 28.0

# --- Автоприціл-асист ---
const AIM_ASSIST_ANGLE := 6.0          # градуси
const AIM_ASSIST_STRENGTH := 5.0
const AIM_ASSIST_RANGE := 60.0

# --- Хвилі ---
const WAVES := [3, 5, 7, 9, 12]
const WAVE_DELAY := 6.0
const WAVE_SPAWN_INTERVAL := 1.8
const MAX_ALIVE := 6
const SPAWN_MIN_DIST := 28.0
const AMMO_PER_WAVE := 60
const GRENADES_PER_WAVE := 1

# --- Керування (типові значення) ---
const DEFAULT_LOOK_SENS := 0.22        # градусів на піксель
const DEFAULT_BUTTON_SCALE := 1.0
const JOYSTICK_RADIUS := 80.0

# --- Вібрація (мс) ---
const VIBRATE_SHOT := 18
const VIBRATE_DAMAGE := 70
const VIBRATE_EXPLOSION := 140
