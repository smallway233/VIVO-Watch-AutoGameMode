import win32gui
import win32ui
import win32con
from PIL import Image
import logging

# 作者：Smallway
# 日期：2026-01-05
# 功能：封装 Windows 图标提取逻辑

logger = logging.getLogger(__name__)

class IconExtractor:
    @staticmethod
    def get_icon(exe_path, width=32, height=32):
        """
        从 EXE 路径提取图标并转换为 PIL Image
        :param exe_path: EXE 文件绝对路径
        :param width: 图标宽度
        :param height: 图标高度
        :return: PIL.Image 对象或 None
        """
        if not exe_path:
            return None

        hIcon = None
        large, small = [], []
        
        try:
            # 1. 提取图标句柄
            # ExtractIconEx 返回 (大图标列表, 小图标列表)
            large, small = win32gui.ExtractIconEx(exe_path, 0)
            
            if not large:
                return None

            hIcon = large[0]

            # 2. 使用 win32ui 绘制图标到内存位图
            hdc_screen = win32gui.GetDC(0)
            hdc = win32ui.CreateDCFromHandle(hdc_screen)
            
            hbmp = win32ui.CreateBitmap()
            hbmp.CreateCompatibleBitmap(hdc, width, height)
            
            hdc_mem = hdc.CreateCompatibleDC()
            hdc_mem.SelectObject(hbmp)
            
            # 绘制图标 (DI_NORMAL)
            win32gui.DrawIconEx(hdc_mem.GetHandleOutput(), 0, 0, hIcon, width, height, 0, 0, win32con.DI_NORMAL)
            
            # 3. 转换为 PIL Image
            bmpinfo = hbmp.GetInfo()
            bmpstr = hbmp.GetBitmapBits(True)
            img = Image.frombuffer(
                'RGB',
                (bmpinfo['bmWidth'], bmpinfo['bmHeight']),
                bmpstr, 'raw', 'BGRX', 0, 1)
            
            # 4. 清理资源
            try:
                hdc_mem.DeleteDC()
                hdc.DeleteDC()
                win32gui.ReleaseDC(0, hdc_screen)
            except Exception as cleanup_error:
                logger.debug(f"资源清理警告: {cleanup_error}")
                
            return img

        except Exception as e:
            logger.error(f"提取图标失败 [{exe_path}]: {e}")
            return None
            
        finally:
            # 确保图标句柄被销毁
            if hIcon:
                try:
                    win32gui.DestroyIcon(hIcon)
                except: pass
            for h in large: 
                if h != hIcon: 
                    try: win32gui.DestroyIcon(h) 
                    except: pass
            for h in small:
                try: win32gui.DestroyIcon(h)
                except: pass
