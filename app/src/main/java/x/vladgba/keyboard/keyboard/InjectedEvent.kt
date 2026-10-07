package x.vladgba.keyboard.keyboard


/*
 * Root-only touch injection through `sendevent` (used by hardware-key remapping with "inject": "1").
 * Constants mirror linux/input-event-codes.h.
 */
private const val CMD = "sendevent"

private const val DOWN = 1
private const val UP = 0

private const val NO_TRACKING_ID = 0xffffffff

// Event types
private const val EV_SYN = 0x00
private const val EV_KEY = 0x01
private const val EV_REL = 0x02
private const val EV_ABS = 0x03
private const val EV_MSC = 0x04
private const val EV_SW = 0x05
private const val EV_LED = 0x11
private const val EV_SND = 0x12
private const val EV_REP = 0x14
private const val EV_FF = 0x15
private const val EV_PWR = 0x16
private const val EV_FF_STATUS = 0x17

// Synchronization events
private const val SYN_REPORT = 0
private const val SYN_CONFIG = 1
private const val SYN_MT_REPORT = 2
private const val SYN_DROPPED = 3

private const val BTN_TOUCH = 0x14a

private const val ABS_MT_SLOT = 0x2f    // MT slot being modified
private const val ABS_MT_TOUCH_MAJOR = 0x30    // Major axis of touching ellipse
private const val ABS_MT_TOUCH_MINOR = 0x31    // Minor axis (omit if circular)
private const val ABS_MT_WIDTH_MAJOR = 0x32    // Major axis of approaching ellipse
private const val ABS_MT_WIDTH_MINOR = 0x33    // Minor axis (omit if circular)
private const val ABS_MT_ORIENTATION = 0x34    // Ellipse orientation
private const val ABS_MT_POSITION_X = 0x35    // Center X touch position
private const val ABS_MT_POSITION_Y = 0x36    // Center Y touch position
private const val ABS_MT_TOOL_TYPE = 0x37    // Type of touching device
private const val ABS_MT_BLOB_ID = 0x38    // Group a set of packets as a blob
private const val ABS_MT_TRACKING_ID = 0x39    // Unique ID of initiated contact
private const val ABS_MT_PRESSURE = 0x3a    // Pressure on contact area
private const val ABS_MT_DISTANCE = 0x3b    // Contact hover distance
private const val ABS_MT_TOOL_X = 0x3c    // Center X tool position
private const val ABS_MT_TOOL_Y = 0x3d    // Center Y tool position

object InjectedEvent {
    var DEV = "/dev/input/event4"
    fun press(x: Int, y: Int, tid: Int, btnTouch: Int = -1, slot: Int = -1) {
        val cmd = CmdList()
        if (slot >= 0) cmd += "$CMD $DEV $EV_ABS $ABS_MT_SLOT $slot"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_TRACKING_ID $tid"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_PRESSURE 20"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_TOUCH_MAJOR 2"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_POSITION_X $x"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_POSITION_Y $y"
        if (btnTouch >= 0) cmd += "$CMD $DEV $EV_KEY $BTN_TOUCH $btnTouch"
        cmd += "$CMD $DEV $EV_SYN $SYN_REPORT 0"
        cmd(cmd.toString())
    }

    private fun cmd(s: String) = KeyAction.suExecBlocking(s)

    fun move(x: Int = -1, y: Int = -1, slot: Int = -1) {
        val cmd = CmdList()
        if (slot >= 0) cmd += "$CMD $DEV $EV_ABS $ABS_MT_SLOT $slot"
        if (x >= 0) cmd += "$CMD $DEV $EV_ABS $ABS_MT_POSITION_X $x"
        if (y >= 0) cmd += "$CMD $DEV $EV_ABS $ABS_MT_POSITION_Y $y"
        cmd += "$CMD $DEV $EV_SYN $SYN_REPORT 0"
        cmd(cmd.toString())
    }

    fun release(btnTouch: Int = -1, slot: Int = -1) {
        val cmd = CmdList()
        if (slot >= 0) cmd += "$CMD $DEV $EV_ABS $ABS_MT_SLOT $slot"
        cmd += "$CMD $DEV $EV_ABS $ABS_MT_TRACKING_ID $NO_TRACKING_ID"
        if (btnTouch >= 0) cmd += "$CMD $DEV $EV_KEY $BTN_TOUCH $btnTouch"
        cmd += "$CMD $DEV $EV_SYN $SYN_REPORT 0"
        cmd(cmd.toString())
    }

    class CmdList {
        private var commands: String = ""

        operator fun plusAssign(s: String) {
            commands += "$s;"
        }

        override fun toString() = commands
    }

}