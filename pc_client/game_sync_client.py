import time
import socket
import psutil
import logging
import json
import os
import threading
import sys
from zeroconf import ServiceBrowser, Zeroconf, ServiceListener
import tkinter as tk
from tkinter import ttk, messagebox, scrolledtext

# 作者：Smallway
# 日期：2026-01-04
# 优化说明：通过延迟加载非必要库（PIL, pystray, simpledialog）和限制日志缓冲区大小，实现运行内存最小化。

# Configure logging
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(message)s')
logger = logging.getLogger(__name__)

# Configuration
CONFIG_FILE = "games.json"
SERVICE_TYPE = "_gamingsync._udp.local."

class ConfigManager:
    def __init__(self):
        self.games = self.load_config()

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, 'r', encoding='utf-8') as f:
                    data = json.load(f)
                    if isinstance(data, list):
                        # Migrate list to dict (Use process name as display name)
                        return {game: game for game in data}
                    return data
            except Exception as e:
                logger.error(f"加载配置失败: {e}")
        return {
            "EldenRing.exe": "Elden Ring",
            "Valorant.exe": "Valorant", 
            "notepad.exe": "记事本",
            "League of Legends.exe": "英雄联盟",
            "chrome.exe": "Google Chrome"
        }

    def save_config(self):
        try:
            with open(CONFIG_FILE, 'w', encoding='utf-8') as f:
                json.dump(self.games, f, indent=4, ensure_ascii=False)
            logger.info("配置已保存")
        except Exception as e:
            logger.error(f"保存配置失败: {e}")

    def add_game(self, process_name, display_name):
        # Update or Add
        self.games[process_name] = display_name
        self.save_config()
        logger.info(f"更新监控规则: {display_name} ({process_name})")
        return True

class GameSyncListener(ServiceListener):
    def __init__(self, monitor=None, on_status_change=None):
        self.device_address = None
        self.device_port = None
        self.monitor = monitor
        self.on_status_change = on_status_change

    def update_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        pass

    def remove_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        logger.info(f"服务移除: {name}")
        if "SmallwayGameSync" in name:
            self.device_address = None
            self.device_port = None
            if self.on_status_change:
                self.on_status_change(False, "")

    def add_service(self, zc: Zeroconf, type_: str, name: str) -> None:
        info = zc.get_service_info(type_, name)
        if info and "SmallwayGameSync" in name:
            self.device_address = socket.inet_ntoa(info.addresses[0])
            self.device_port = info.port
            logger.info(f"发现 Android端，地址: {self.device_address}:{self.device_port}")
            if self.on_status_change:
                self.on_status_change(True, f"{self.device_address}:{self.device_port}")
            if self.monitor:
                 self.monitor.on_connected()

class GameMonitor:
    def __init__(self, config_manager):
        self.listener = None
        self.is_gaming = False
        self.current_game_name = ""
        self.current_process = ""
        self.start_timestamp = 0
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.bind(('', 0))
        self.sock.settimeout(1.0)
        self.config_manager = config_manager
        self.running = True
        
        # Heartbeat variables
        self.last_heartbeat_send_time = 0
        self.last_response_time = time.time()
        self.is_connected_via_heartbeat = False

    def set_listener(self, listener):
        self.listener = listener

    def on_connected(self):
        self.send_command("CONNECT")
        # Reset heartbeat timer on new connection
        self.last_response_time = time.time()
        if self.is_gaming:
            logger.info("游戏已在运行，重新发送启动指令")
            self.send_command("START", game=self.current_game_name, time=self.start_timestamp)

    def send_command(self, cmd, **kwargs):
        if self.listener and self.listener.device_address and self.listener.device_port:
            try:
                payload = {"cmd": cmd}
                payload.update(kwargs)
                message = json.dumps(payload).encode('utf-8')
                self.sock.sendto(message, (self.listener.device_address, self.listener.device_port))
                if cmd != "HEARTBEAT": # Reduce log noise
                    logger.info(f"发送指令: {json.dumps(payload)}")
            except Exception as e:
                logger.error(f"发送指令失败: {e}")
        else:
            # logger.warning("Device not connected. Cannot send command.") 
            pass

    def check_processes(self):
        found_process = None
        found_game_name = None
        
        # psutil.process_iter is a generator, efficient for memory
        # 优化：使用生成器遍历进程，避免一次性加载所有进程信息到内存
        # 作者：Smallway，日期：2026-01-04
        for proc in psutil.process_iter(['name']):
            try:
                pname = proc.info['name']
                if pname in self.config_manager.games:
                    found_process = pname
                    found_game_name = self.config_manager.games[pname]
                    break
            except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess):
                pass
        
        if found_process:
            if not self.is_gaming:
                # Game just started
                self.is_gaming = True
                self.current_game_name = found_game_name
                self.current_process = found_process
                self.start_timestamp = int(time.time() * 1000) # MS timestamp
                logger.info(f"游戏启动: {found_game_name} ({found_process})")
                self.send_command("START", game=found_game_name, time=self.start_timestamp)
            elif self.current_process != found_process:
                # Switched game?
                logger.info(f"切换游戏至: {found_game_name} ({found_process})")
                self.current_game_name = found_game_name
                self.current_process = found_process
                self.start_timestamp = int(time.time() * 1000)
                self.send_command("START", game=found_game_name, time=self.start_timestamp)
        else:
            if self.is_gaming:
                # Game stopped
                logger.info(f"检测到游戏停止: {self.current_game_name}")
                self.send_command("STOP", game=self.current_game_name, time=self.start_timestamp) # Send last info for history
                self.is_gaming = False
                self.current_game_name = ""
                self.current_process = ""
                self.start_timestamp = 0

    def receive_loop(self):
        while self.running:
            try:
                data, addr = self.sock.recvfrom(1024)
                try:
                    msg = json.loads(data.decode('utf-8'))
                    if msg.get('cmd') == 'HEARTBEAT_ACK':
                        self.last_response_time = time.time()
                        if not self.is_connected_via_heartbeat:
                             self.is_connected_via_heartbeat = True
                             logger.info(f"收到心跳确认，来自: {addr}")
                             if self.listener and self.listener.on_status_change:
                                 self.listener.on_status_change(True, f"{self.listener.device_address}:{self.listener.device_port}")
                except json.JSONDecodeError:
                    pass
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    logger.error(f"接收错误: {e}")
                time.sleep(1)

    def handle_heartbeat(self):
        # 心跳机制处理逻辑
        # 作者：Smallway
        # 日期：2026-01-05
        if self.listener and self.listener.device_address and self.listener.device_port:
            current_time = time.time()
            
            # Send Heartbeat every 3 seconds (Adjusted for better stability)
            # 每隔3秒发送一次心跳包，告诉手机端自己在线
            if current_time - self.last_heartbeat_send_time >= 3:
                self.send_command("HEARTBEAT")
                self.last_heartbeat_send_time = current_time
            
            # Check Timeout (15 seconds)
            # 检查心跳超时（15秒未收到确认则认为连接断开）
            if self.is_connected_via_heartbeat:
                if current_time - self.last_response_time > 15:
                    self.is_connected_via_heartbeat = False
                    logger.warning("连接超时 (未收到心跳确认)")
                    if self.listener and self.listener.on_status_change:
                        self.listener.on_status_change(False, "连接丢失 (超时)")

    def run(self):
        logger.info("开始游戏监控循环...")
        
        # Start receive thread
        receive_thread = threading.Thread(target=self.receive_loop, daemon=True)
        receive_thread.start()
        
        while self.running:
            self.check_processes()
            self.handle_heartbeat()
            time.sleep(1)

    def stop(self):
        self.running = False
        try:
            self.sock.close()
        except:
            pass

def scan_processes():
    # 返回 (process_name, exe_path) 列表
    procs = []
    seen = set()
    for proc in psutil.process_iter(['name', 'exe']):
        try:
            name = proc.info['name']
            exe = proc.info['exe']
            if name and name not in seen:
                procs.append((name, exe))
                seen.add(name)
        except:
            pass
    # 按名称排序
    return sorted(procs, key=lambda x: x[0].lower())

# Windows Icon Extraction Utilities
# Refactored to use IconExtractor class
# 日期：2026-01-05
try:
    from icon_extractor import IconExtractor
except ImportError:
    IconExtractor = None


# Redirect logging to Text widget
class TextHandler(logging.Handler):
    def __init__(self, text_widget):
        logging.Handler.__init__(self)
        self.text_widget = text_widget

    def emit(self, record):
        msg = self.format(record)
        def append():
            self.text_widget.configure(state='normal')
            self.text_widget.insert(tk.END, msg + '\n')
            
            # 优化：限制日志缓冲区行数，防止内存无限增长
            # 作者：Smallway，日期：2026-01-04
            try:
                # 获取当前行数，格式为 "line.char"
                num_lines = int(self.text_widget.index('end-1c').split('.')[0])
                if num_lines > 200: # 仅保留最近200行
                    self.text_widget.delete('1.0', '2.0') # 删除第一行
            except Exception:
                pass
                
            self.text_widget.configure(state='disabled')
            self.text_widget.see(tk.END)
        self.text_widget.after(0, append)

class GameSyncApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Smallway 游戏联动 PC 客户端")
        self.root.geometry("600x500")
        
        # Dark Theme Colors
        self.bg_color = "#2b2b2b"
        self.fg_color = "#ffffff"
        self.accent_color = "#00FF00" # Matrix Green style
        self.list_bg = "#3c3f41"
        
        self.root.configure(bg=self.bg_color)
        
        # Style configuration
        style = ttk.Style()
        style.theme_use('clam')
        style.configure("TLabel", background=self.bg_color, foreground=self.fg_color)
        style.configure("TButton", background="#404040", foreground="white", borderwidth=1)
        style.map("TButton", background=[('active', '#505050')])
        style.configure("TFrame", background=self.bg_color)
        
        # Data Managers
        self.config_manager = ConfigManager()
        self.monitor = GameMonitor(self.config_manager)
        self.listener = GameSyncListener(self.monitor, self.update_connection_status)
        self.monitor.set_listener(self.listener)
        
        # Zeroconf
        self.zeroconf = Zeroconf()
        self.browser = ServiceBrowser(self.zeroconf, SERVICE_TYPE, self.listener)
        
        # Setup UI
        self.setup_ui()
        
        # Start Monitor Thread
        self.monitor_thread = threading.Thread(target=self.monitor.run, daemon=True)
        self.monitor_thread.start()

    def setup_ui(self):
        # Header / Connection Status
        header_frame = ttk.Frame(self.root)
        header_frame.pack(fill=tk.X, padx=10, pady=10)
        
        self.status_label = ttk.Label(header_frame, text="状态: 正在搜索设备...", font=("微软雅黑", 12))
        self.status_label.pack(side=tk.LEFT)
        
        # Main Content
        content_frame = ttk.Frame(self.root)
        content_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        # Left Panel: Game List
        left_panel = ttk.Frame(content_frame)
        left_panel.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=(0, 5))
        
        ttk.Label(left_panel, text="监控游戏列表", font=("微软雅黑", 10, "bold")).pack(anchor=tk.W)
        
        list_frame = ttk.Frame(left_panel)
        list_frame.pack(fill=tk.BOTH, expand=True, pady=5)
        
        self.game_listbox = tk.Listbox(list_frame, bg=self.list_bg, fg=self.fg_color, selectbackground="#505050", borderwidth=0)
        scrollbar = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.game_listbox.yview)
        self.game_listbox.configure(yscrollcommand=scrollbar.set)
        
        self.game_listbox.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        
        self.refresh_game_list()
        
        # Right Panel: Actions
        right_panel = ttk.Frame(content_frame)
        right_panel.pack(side=tk.RIGHT, fill=tk.Y, padx=(5, 0))
        
        ttk.Button(right_panel, text="手动添加游戏", command=self.add_game_manual).pack(fill=tk.X, pady=2)
        ttk.Button(right_panel, text="扫描并添加进程", command=self.scan_and_add).pack(fill=tk.X, pady=2)
        ttk.Button(right_panel, text="移除选中游戏", command=self.remove_game).pack(fill=tk.X, pady=2)
        
        # Log Area
        log_frame = ttk.LabelFrame(self.root, text="日志", padding=5)
        log_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=10)
        
        self.log_text = scrolledtext.ScrolledText(log_frame, height=8, bg="black", fg="#00FF00", font=("Consolas", 9))
        self.log_text.pack(fill=tk.BOTH, expand=True)
        self.log_text.configure(state='disabled')
        
        # Redirect logger
        text_handler = TextHandler(self.log_text)
        text_handler.setFormatter(logging.Formatter('%(asctime)s - %(message)s', datefmt='%H:%M:%S'))
        logger.addHandler(text_handler)

    def update_connection_status(self, connected, address):
        if connected:
            self.status_label.config(text=f"状态: 已连接至 {address}", foreground="#00FF00")
        else:
            self.status_label.config(text="状态: 正在搜索设备...", foreground="white")

    def refresh_game_list(self):
        self.game_listbox.delete(0, tk.END)
        for process, name in self.config_manager.games.items():
            self.game_listbox.insert(tk.END, f"{name} ({process})")

    def add_game_manual(self):
        def save():
            process = entry_process.get().strip()
            name = entry_name.get().strip()
            if process:
                if not name:
                    name = process
                if self.config_manager.add_game(process, name):
                    self.refresh_game_list()
                    top.destroy()
            else:
                messagebox.showwarning("提示", "请输入进程名称")
        
        top = tk.Toplevel(self.root)
        top.title("添加游戏")
        top.geometry("300x180")
        top.configure(bg=self.bg_color)
        
        tk.Label(top, text="进程名称 (例如 game.exe):", bg=self.bg_color, fg=self.fg_color).pack(pady=5)
        entry_process = tk.Entry(top)
        entry_process.pack(pady=5, padx=10, fill=tk.X)
        entry_process.focus()
        
        tk.Label(top, text="显示名称 (例如 我的游戏):", bg=self.bg_color, fg=self.fg_color).pack(pady=5)
        entry_name = tk.Entry(top)
        entry_name.pack(pady=5, padx=10, fill=tk.X)
        
        tk.Button(top, text="保存", command=save, bg="#404040", fg="white").pack(pady=10)

    def scan_and_add(self):
        # 优化：延迟加载 simpledialog，仅在需要时导入
        # 作者：Smallway，日期：2026-01-04
        from tkinter import simpledialog
        from PIL import ImageTk, Image

        # 同步扫描进程和图标
        # 作者：Smallway，日期：2026-01-05
        # 注意：这里可能会有轻微卡顿，因为需要提取图标
        procs = scan_processes()
        
        top = tk.Toplevel(self.root)
        top.title("选择进程")
        top.geometry("450x550")
        top.configure(bg=self.bg_color)
        
        # Keep reference to images to prevent GC
        self.scan_icons = []
        
        # Use Treeview for Icons
        # Simplified to single column for Icon+Name
        tree = ttk.Treeview(top, show="tree", selectmode="browse", height=20)
        tree.column("#0", width=400, anchor="w")
        tree.heading("#0", text="进程列表 (部分系统进程可能无法获取图标)")
        
        # Style adjustments for row height
        style = ttk.Style()
        style.configure("Treeview", rowheight=24, background=self.list_bg, foreground=self.fg_color, fieldbackground=self.list_bg)
        
        # Ensure text is visible with tags
        tree.tag_configure('normal_row', foreground=self.fg_color, background=self.list_bg)
        
        scrollbar = ttk.Scrollbar(top, orient=tk.VERTICAL, command=tree.yview)
        tree.configure(yscrollcommand=scrollbar.set)
        
        tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True, padx=5, pady=5)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y, pady=5)
        
        # Default icon (gray placeholder)
        default_img = Image.new('RGB', (20, 20), color='#505050')
        self.default_photo = ImageTk.PhotoImage(default_img)
        
        # Populate Treeview
        count_icons = 0
        for name, exe_path in procs:
            photo_icon = self.default_photo
            
            try:
                # Extract icon
                if exe_path and IconExtractor:
                    pil_icon = IconExtractor.get_icon(exe_path)
                    if pil_icon:
                        # Resize to 20x20
                        pil_icon = pil_icon.resize((20, 20), Image.Resampling.LANCZOS)
                        photo_icon = ImageTk.PhotoImage(pil_icon)
                        self.scan_icons.append(photo_icon) # Keep reference
                        count_icons += 1
            except Exception as e:
                print(f"Icon error for {name}: {e}")
            
            # Insert into #0 column (Icon + Text)
            tree.insert("", "end", text=f" {name}", image=photo_icon, values=(name,), tags=('normal_row',))
            
        print(f"Loaded {count_icons} icons from {len(procs)} processes")
             
        def add_selected():
            selection = tree.selection()
            if selection:
                item = tree.item(selection[0])
                # Store real process name in values to avoid parsing text
                process_name = item['values'][0] 
                
                display_name = simpledialog.askstring("输入名称", f"请输入 {process_name} 的显示名称:", initialvalue=process_name, parent=top)
                
                if display_name:
                    self.config_manager.add_game(process_name, display_name)
                    self.refresh_game_list()
                    top.destroy()
                
        tk.Button(top, text="添加选中项", command=add_selected, bg="#404040", fg="white").pack(fill=tk.X, side=tk.BOTTOM)

    def remove_game(self):
        selection = self.game_listbox.curselection()
        if selection:
            item_text = self.game_listbox.get(selection[0])
            # Parse "Display Name (process.exe)" -> process.exe
            try:
                # Find last occurrence of " ("
                last_open_paren = item_text.rfind(" (")
                if last_open_paren != -1:
                    process = item_text[last_open_paren+2:-1]
                    if messagebox.askyesno("确认", f"确定要移除 {process} 吗?"):
                         if process in self.config_manager.games:
                            del self.config_manager.games[process]
                            self.config_manager.save_config()
                            self.refresh_game_list()
                            logger.info(f"移除游戏规则: {process}")
            except Exception as e:
                logger.error(f"移除失败: {e}")

    def create_tray_icon(self):
        # 优化：延迟加载 PIL 库，仅在最小化时加载
        # 作者：Smallway，日期：2026-01-04
        from PIL import Image, ImageDraw
        
        # Create a simple icon image using PIL
        width = 64
        height = 64
        color1 = (0, 0, 0)
        color2 = (0, 255, 0)
        
        image = Image.new('RGB', (width, height), color1)
        dc = ImageDraw.Draw(image)
        dc.rectangle((16, 16, 48, 48), fill=color2)
        
        return image

    def minimize_to_tray(self):
        # 优化：延迟加载 pystray 库，仅在最小化时加载
        # 作者：Smallway，日期：2026-01-04
        import pystray
        
        self.root.withdraw()
        image = self.create_tray_icon()
        menu = pystray.Menu(
            pystray.MenuItem("显示主界面", self.restore_from_tray),
            pystray.MenuItem("退出", self.quit_from_tray)
        )
        self.icon = pystray.Icon("VivoGameSync", image, "Vivo 游戏联动", menu)
        self.icon.run()

    def restore_from_tray(self, icon, item):
        icon.stop()
        self.root.after(0, self.root.deiconify)

    def quit_from_tray(self, icon, item):
        icon.stop()
        self.root.after(0, self.cleanup_and_exit)

    def cleanup_and_exit(self):
        self.monitor.stop()
        self.zeroconf.close()
        self.root.destroy()
        sys.exit(0)

    def on_close(self):
        ans = messagebox.askyesnocancel("退出", "您想要退出程序还是最小化到托盘？\n\n是(Yes): 退出程序\n否(No): 最小化到托盘\n取消(Cancel): 取消操作")
        if ans is True: # Yes -> Quit
            self.cleanup_and_exit()
        elif ans is False: # No -> Minimize
            self.minimize_to_tray()
        else: # Cancel
            pass

if __name__ == '__main__':
    root = tk.Tk()
    app = GameSyncApp(root)
    root.protocol("WM_DELETE_WINDOW", app.on_close)
    root.mainloop()
