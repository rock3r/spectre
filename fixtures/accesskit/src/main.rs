use accesskit::{Action, ActionRequest, ActivationHandler, TreeUpdate};
use accesskit_winit::{Adapter, Event as AccessKitEvent, WindowEvent as AccessKitWindowEvent};
use spectre_accesskit_fixture::{initial_tree, POPOVER_TRIGGER_ID};
use std::error::Error;
use std::sync::{Arc, Mutex};
use winit::application::ApplicationHandler;
use winit::event::WindowEvent;
use winit::event_loop::{ActiveEventLoop, EventLoop, EventLoopProxy};
use winit::window::{Window, WindowId};

struct FixtureState {
    popover_open: bool,
}
impl FixtureState {
    fn tree(&self) -> TreeUpdate {
        initial_tree(self.popover_open)
    }
}

struct Activation {
    state: Arc<Mutex<FixtureState>>,
}
impl ActivationHandler for Activation {
    fn request_initial_tree(&mut self) -> Option<TreeUpdate> {
        Some(self.state.lock().unwrap().tree())
    }
}

struct WindowState {
    window: Window,
    adapter: Adapter,
    state: Arc<Mutex<FixtureState>>,
}
struct App {
    proxy: EventLoopProxy<AccessKitEvent>,
    window: Option<WindowState>,
}

impl ApplicationHandler<AccessKitEvent> for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let attrs = Window::default_attributes()
            .with_title("Spectre AccessKit Fixture")
            .with_inner_size(winit::dpi::LogicalSize::new(640, 480))
            .with_visible(false);
        let window = event_loop
            .create_window(attrs)
            .expect("create fixture window");
        let state = Arc::new(Mutex::new(FixtureState {
            popover_open: false,
        }));
        let adapter = Adapter::with_mixed_handlers(
            event_loop,
            &window,
            Activation {
                state: state.clone(),
            },
            self.proxy.clone(),
        );
        window.set_visible(true);
        println!("SPECTRE_ACCESSKIT_FIXTURE_READY pid={}", std::process::id());
        self.window = Some(WindowState {
            window,
            adapter,
            state,
        });
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        let Some(state) = &mut self.window else {
            return;
        };
        state.adapter.process_event(&state.window, &event);
        if event == WindowEvent::CloseRequested {
            event_loop.exit();
        }
    }

    fn user_event(&mut self, _: &ActiveEventLoop, event: AccessKitEvent) {
        let Some(window) = &mut self.window else {
            return;
        };
        if let AccessKitWindowEvent::ActionRequested(ActionRequest {
            action: Action::Click,
            target_node,
            ..
        }) = event.window_event
        {
            if target_node == POPOVER_TRIGGER_ID {
                let mut state = window.state.lock().unwrap();
                state.popover_open = !state.popover_open;
                window.adapter.update_if_active(|| state.tree());
            }
        }
    }
}

fn main() -> Result<(), Box<dyn Error>> {
    let event_loop = EventLoop::with_user_event().build()?;
    let mut app = App {
        proxy: event_loop.create_proxy(),
        window: None,
    };
    event_loop.run_app(&mut app)?;
    Ok(())
}
