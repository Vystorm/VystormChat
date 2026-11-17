package at.esoren.vystormchat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.compat.ServerCompat;
import Vystorm.vystorm_core.compat.dialog.DialogInterface;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
    @Test
    void givenCoreWithoutPanelClasses_whenPluginAndChestMenusLoad_thenTheirSignaturesStillLink() throws Exception {
        var source = ChatPlugin.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{source}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("Vystorm.vystorm_core.panel.")) throw new ClassNotFoundException(name);
                if (name.startsWith("at.esoren.vystormchat.")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            for (String name : java.util.List.of("ChatPlugin", "ChatMenu", "Reports", "Showcase", "Menu")) {
                Class<?> type = Class.forName("at.esoren.vystormchat." + name, true, loader);
                assertSame(loader, type.getClassLoader());
                assertTrue(type.getDeclaredMethods().length > 0);
            }
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("Vystorm.vystorm_core.panel.Panels"));
        }
    }

    @Test
    void detectsNativeDialogsFromTheRuntimeApi() {
        assertEquals(!Boolean.getBoolean("chat.test.legacy"), ServerCompat.hasDialogs());
        assertFalse(ServerCompat.hasClass("io.papermc.paper.DoesNotExist"));
        assertTrue(ServerCompat.hasClass("org.bukkit.entity.Player"));
    }

    @Test
    void chatFallbackLoadsWithoutNativeDialogClasses() throws Exception {
        Object handler = Class.forName("Vystorm.vystorm_core.compat.internal.LegacyDialogs").getConstructor().newInstance();
        assertInstanceOf(DialogInterface.class, handler);
    }

    @Test
    void inventoryAccessWorksWithBothClassAndInterfaceViews() {
        Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class, invocation ->
                invocation.getMethod().getName().equals("getTopInventory") ? top : null);
        assertSame(top, InventoryCompat.top(view));
    }

    @Test
    void showcaseRemainsReadOnlyOnBothApis() {
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        Inventory top = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class, invocation ->
                invocation.getMethod().getName().equals("getTopInventory") ? top : null);
        when(event.getView()).thenReturn(view);
        when(top.getHolder()).thenReturn(new Showcase.View());
        new Showcase.Clicks(Vystorm.vystorm_core.compat.InventoryCompatInterface.DEFAULT).onDrag(event);
        verify(event).setCancelled(true);
    }
}
