import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Items;

/** Build/research only. Reads the pinned official server's actual numeric registries, not MCPL guesses. */
public final class RoleSelectorRegistryFixture {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        int head = BuiltInRegistries.ITEM.getId(Items.PLAYER_HEAD);
        int nameTag = BuiltInRegistries.ITEM.getId(Items.NAME_TAG);
        int diamond = BuiltInRegistries.ITEM.getId(Items.DIAMOND);
        int menu = BuiltInRegistries.MENU.getId(MenuType.GENERIC_9x3);
        if (head <= 0 || nameTag <= 0 || diamond <= 0 || menu < 0) throw new IllegalStateException("Official selector registry entries missing");
        System.out.println("ROLE_FIXTURE minecraft:player_head=" + head);
        System.out.println("ROLE_FIXTURE minecraft:name_tag=" + nameTag);
        System.out.println("ROLE_FIXTURE minecraft:diamond=" + diamond);
        System.out.println("ROLE_FIXTURE name-tag-key=" + BuiltInRegistries.ITEM.getKey(Items.NAME_TAG));
        System.out.println("ROLE_FIXTURE diamond-key=" + BuiltInRegistries.ITEM.getKey(Items.DIAMOND));
        System.out.println("ROLE_FIXTURE minecraft:generic_9x3=" + menu);
        System.out.println("ROLE_FIXTURE item-key=" + BuiltInRegistries.ITEM.getKey(Items.PLAYER_HEAD));
        System.out.println("ROLE_FIXTURE menu-key=" + BuiltInRegistries.MENU.getKey(MenuType.GENERIC_9x3));
    }
}
