//! AWT VK / button mask → RemoteDesktop Notify* wire values.

/// Linux evdev BTN_LEFT / BTN_RIGHT / BTN_MIDDLE.
pub const BTN_LEFT: i32 = 0x110;
pub const BTN_RIGHT: i32 = 0x111;
pub const BTN_MIDDLE: i32 = 0x112;

/// AWT `InputEvent.BUTTON1/2/3_DOWN_MASK`.
pub const AWT_BUTTON1_DOWN_MASK: i32 = 1 << 10;
pub const AWT_BUTTON2_DOWN_MASK: i32 = 1 << 11;
pub const AWT_BUTTON3_DOWN_MASK: i32 = 1 << 12;

pub fn awt_button_mask_to_evdev(mask: i32) -> anyhow::Result<i32> {
    match mask {
        AWT_BUTTON1_DOWN_MASK => Ok(BTN_LEFT),
        AWT_BUTTON3_DOWN_MASK => Ok(BTN_RIGHT),
        AWT_BUTTON2_DOWN_MASK => Ok(BTN_MIDDLE),
        other => anyhow::bail!("unsupported AWT mouse button mask {other}"),
    }
}

/// X11 keysym for a `java.awt.event.KeyEvent.VK_*` code (US keyboard).
pub fn vk_to_keysym(vk: i32) -> anyhow::Result<i32> {
    const XK_BACKSPACE: i32 = 0xff08;
    const XK_TAB: i32 = 0xff09;
    const XK_RETURN: i32 = 0xff0d;
    const XK_ESCAPE: i32 = 0xff1b;
    const XK_DELETE: i32 = 0xffff;
    const XK_HOME: i32 = 0xff50;
    const XK_LEFT: i32 = 0xff51;
    const XK_UP: i32 = 0xff52;
    const XK_RIGHT: i32 = 0xff53;
    const XK_DOWN: i32 = 0xff54;
    const XK_END: i32 = 0xff57;
    const XK_SHIFT_L: i32 = 0xffe1;
    const XK_CONTROL_L: i32 = 0xffe3;
    const XK_ALT_L: i32 = 0xffe9;
    const XK_META_L: i32 = 0xffe7;
    const XK_SPACE: i32 = 0x0020;

    // java.awt.event.KeyEvent
    const VK_ENTER: i32 = 10;
    const VK_BACK_SPACE: i32 = 8;
    const VK_TAB: i32 = 9;
    const VK_SHIFT: i32 = 16;
    const VK_CONTROL: i32 = 17;
    const VK_ALT: i32 = 18;
    const VK_META: i32 = 157;
    const VK_ESCAPE: i32 = 27;
    const VK_SPACE: i32 = 32;
    const VK_END: i32 = 35;
    const VK_HOME: i32 = 36;
    const VK_LEFT: i32 = 37;
    const VK_UP: i32 = 38;
    const VK_RIGHT: i32 = 39;
    const VK_DOWN: i32 = 40;
    const VK_DELETE: i32 = 127;
    const VK_A: i32 = 65;
    const VK_Z: i32 = 90;
    const VK_0: i32 = 48;
    const VK_9: i32 = 57;

    match vk {
        VK_ENTER => Ok(XK_RETURN),
        VK_BACK_SPACE => Ok(XK_BACKSPACE),
        VK_TAB => Ok(XK_TAB),
        VK_ESCAPE => Ok(XK_ESCAPE),
        VK_DELETE => Ok(XK_DELETE),
        VK_HOME => Ok(XK_HOME),
        VK_END => Ok(XK_END),
        VK_LEFT => Ok(XK_LEFT),
        VK_UP => Ok(XK_UP),
        VK_RIGHT => Ok(XK_RIGHT),
        VK_DOWN => Ok(XK_DOWN),
        VK_SHIFT => Ok(XK_SHIFT_L),
        VK_CONTROL => Ok(XK_CONTROL_L),
        VK_ALT => Ok(XK_ALT_L),
        VK_META => Ok(XK_META_L),
        VK_SPACE => Ok(XK_SPACE),
        vk if (VK_A..=VK_Z).contains(&vk) => Ok(vk + 32), // XK_a..XK_z
        vk if (VK_0..=VK_9).contains(&vk) => Ok(vk),    // XK_0..XK_9
        other => punctuation_vk_to_keysym(other),
    }
}

fn punctuation_vk_to_keysym(vk: i32) -> anyhow::Result<i32> {
    // VK_COMMA=44, VK_MINUS=45, VK_PERIOD=46, VK_SLASH=47, VK_SEMICOLON=59, VK_EQUALS=61,
    // VK_OPEN_BRACKET=91, VK_BACK_SLASH=92, VK_CLOSE_BRACKET=93, VK_BACK_QUOTE=192, VK_QUOTE=222
    match vk {
        44 => Ok(0x002c),  // comma
        45 => Ok(0x002d),  // minus
        46 => Ok(0x002e),  // period
        47 => Ok(0x002f),  // slash
        59 => Ok(0x003b),  // semicolon
        61 => Ok(0x003d),  // equal
        91 => Ok(0x005b),  // bracketleft
        92 => Ok(0x005c),  // backslash
        93 => Ok(0x005d),  // bracketright
        192 => Ok(0x0060), // grave
        222 => Ok(0x0027), // apostrophe
        other => anyhow::bail!("unsupported AWT punctuation key code {other}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn left_click_mask_maps_to_btn_left() {
        assert_eq!(awt_button_mask_to_evdev(AWT_BUTTON1_DOWN_MASK).unwrap(), BTN_LEFT);
        assert_eq!(awt_button_mask_to_evdev(AWT_BUTTON3_DOWN_MASK).unwrap(), BTN_RIGHT);
        assert!(awt_button_mask_to_evdev(0).is_err());
    }

    #[test]
    fn letter_and_enter_keysyms() {
        assert_eq!(vk_to_keysym(65).unwrap(), 0x0061); // VK_A → XK_a
        assert_eq!(vk_to_keysym(10).unwrap(), 0xff0d); // VK_ENTER → XK_Return
        assert_eq!(vk_to_keysym(9).unwrap(), 0xff09); // VK_TAB
        assert_eq!(vk_to_keysym(36).unwrap(), 0xff50); // VK_HOME → XK_Home
        assert_eq!(vk_to_keysym(35).unwrap(), 0xff57); // VK_END → XK_End
        assert!(vk_to_keysym(9999).is_err());
    }
}
