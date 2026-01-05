import psutil
from icon_extractor import IconExtractor
import traceback

if __name__ == "__main__":
    # 自动查找一个存在的进程进行测试，例如 explorer.exe
    target_pid = None
    target_exe = None
    
    for proc in psutil.process_iter(['name', 'exe']):
        try:
            if proc.info['name'] and proc.info['name'].lower() == 'explorer.exe':
                target_pid = proc.pid
                target_exe = proc.info['exe']
                break
        except: pass
    
    if target_exe:
        print(f"正在测试进程: explorer.exe (PID: {target_pid})")
        print(f"路径: {target_exe}")
        
        img = IconExtractor.get_icon(target_exe)
        
        if img:
            save_path = "icon_test_class.png"
            img.save(save_path)
            print(f"图标已保存至: {save_path}")
        else:
            print("提取失败，返回 None")
            
    else:
        print("未找到 explorer.exe")
