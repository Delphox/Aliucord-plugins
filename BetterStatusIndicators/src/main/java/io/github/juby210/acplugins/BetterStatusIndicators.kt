/*
 * Copyright (c) 2021-2022 Juby210
 * Licensed under the Open Software License version 3.0
 */

package io.github.juby210.acplugins

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import com.aliucord.PluginManager
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.Hook
import com.discord.api.presence.ClientStatus
import com.discord.api.presence.ClientStatuses
import com.discord.databinding.*
import com.discord.models.presence.Presence
import com.discord.utilities.presence.PresenceUtils
import com.discord.views.StatusView
import com.discord.views.user.UserAvatarPresenceView
import com.discord.widgets.channels.list.WidgetChannelsListAdapter
import com.discord.widgets.channels.list.items.ChannelListItem
import com.discord.widgets.channels.list.items.ChannelListItemPrivate
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListAdapter
import com.discord.widgets.channels.memberlist.adapter.ChannelMembersListViewHolderMember
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapterItemMessage
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.friends.FriendsListViewModel
import com.discord.widgets.friends.WidgetFriendsListAdapter
import com.discord.widgets.user.profile.UserProfileHeaderView
import com.discord.widgets.user.profile.UserProfileHeaderViewModel
import com.facebook.drawee.span.SimpleDraweeSpanTextView
import com.lytefast.flexinput.R
import io.github.juby210.acplugins.bsi.*

@AliucordPlugin
@SuppressLint("UseCompatLoadingForDrawables")
class BetterStatusIndicators : Plugin() {
    init {
        needsResources = true
        settingsTab = SettingsTab(PluginSettings::class.java).withArgs(this)
    }

    private lateinit var mobile: Array<Drawable>
    private lateinit var desktop: Array<Drawable>
    private lateinit var web: Array<Drawable>
    private lateinit var filled: Array<Drawable>
    private lateinit var radialDrawables: Array<Drawable>
    private lateinit var radialDrawablesRect: Array<Drawable>

    private val usernameTextId = Utils.getResId("username_text", "id")

    override fun start(context: Context) {
        if (isPluginEnabled("BetterStatus")) {
            Utils.showToast(
                "BetterStatusIndicators automatically disabled BetterStatus for you as those plugins are incompatible each other.",
                true
            )
            PluginManager.disablePlugin("BetterStatus")
        }

        val res = context.resources
        mobile = res.getStatusDrawables(res.getDrawable(R.e.ic_mobile, null))
        filled = res.getStatusDrawables(res.getDrawable(R.e.ic_status_online_16dp, null))

        resources.run {
            desktop = res.getStatusDrawables(getPluginDrawable("ic_desktop"))
            web = res.getStatusDrawables(getPluginDrawable("ic_web"))
            radialDrawables = res.getStatusDrawables(getPluginDrawable("ic_radial_status"))
            radialDrawablesRect = res.getStatusDrawables(getPluginDrawable("ic_radial_status_rect"))
        }

        val square = isPluginEnabled("SquareAvatars")

        patchStatusView(res)
        patchMembersList(square)

        // User profile
        patcher.patch(
            UserProfileHeaderView::class.java.getDeclaredMethod("configurePrimaryName", UserProfileHeaderViewModel.ViewState.Loaded::class.java),
            Hook {
                val presence = (it.args[0] as UserProfileHeaderViewModel.ViewState.Loaded).presence ?: return@Hook
                val clientStatuses = presence.clientStatuses ?: return@Hook

                val usernameText = (it.thisObject as View).findViewById<SimpleDraweeSpanTextView?>(usernameTextId) ?: return@Hook
                addIndicators(usernameText, clientStatuses, settings.getInt("sizeUserProfileInd", 32))
            }
        )

        patchDMsList(square)
        patchFriendsList(square)
        patchChatStatus()
        patchChatStatusPlatforms()
        patchRadialStatus(settings.radialStatus, square)
    }

    override fun stop(context: Context?) = patcher.unpatchAll()

    private fun Resources.getStatusDrawables(drawable: Drawable) = arrayOf(
        drawable.clone().apply {
            setStatusTint("colorOnline", this@getStatusDrawables, R.c.status_green_600)
        },
        drawable.clone().apply {
            setStatusTint("colorIdle", this@getStatusDrawables, R.c.status_yellow)
        },
        drawable.clone().apply {
            setStatusTint("colorDND", this@getStatusDrawables, R.c.status_red)
        }
    )

    private fun Drawable.setStatusTint(key: String, res: Resources, default: Int) =
        setTint(settings.getInt(key, res.getColor(default, null) - 1))

    private fun TextView.appendIcon(icon: Drawable, size: Int, width: Int = size) {
        append(" ")
        append(SpannableStringBuilder().append(" ", ImageSpan(
            icon.clone().apply { setBounds(0, 0, width, size) }, 1
        ), 0))
    }

    private fun addIndicators(
        view: TextView,
        clientStatuses: ClientStatuses,
        size: Int,
        noAvatarStatus: Boolean = !settings.getBool("avatarStatus", true)
    ) {
        val isMobile = clientStatuses.mobile?.isActive ?: false
        if (noAvatarStatus && isMobile) clientStatuses.mobile?.let { mobileStatus ->
            mobileStatus.getDrawable(mobile)?.apply { view.appendIcon(this, size, (size / 1.5).toInt()) }
        }
        if (noAvatarStatus || isMobile) clientStatuses.desktop?.let { desktopStatus ->
            desktopStatus.getDrawable(desktop)?.apply { view.appendIcon(this, size) }
        }
        if (noAvatarStatus || isMobile || clientStatuses.desktop?.isActive == true) clientStatuses.web?.let { webStatus ->
            webStatus.getDrawable(web)?.apply { view.appendIcon(this, size) }
        }
    }

    private var unpatchStatusView: Runnable? = null
    fun patchStatusView(res: Resources) {
        unpatchStatusView?.run()

        val m = StatusView::class.java.getDeclaredMethod("setPresence", Presence::class.java)
        if (settings.getBool("avatarStatus", true))
            unpatchStatusView = patcher.patch(m, Hook {
                val presence = it.args[0] as Presence? ?: return@Hook
                val clientStatuses = presence.clientStatuses ?: return@Hook

                val statusView = it.thisObject as StatusView

                clientStatuses.mobile?.let { mobileStatus ->
                    if (PresenceUtils.INSTANCE.isMobile(clientStatuses) && !settings.exists("colorOnline")) return@Hook
                    mobileStatus.getDrawable(mobile)?.apply {
                        statusView.setImageDrawable(this)
                        return@Hook
                    }
                }

/*                clientStatuses.desktop?.let { desktopStatus ->
                    desktopStatus.getDrawable(desktop)?.apply {
                        statusView.setImageDrawable(this)
                        return@Hook
                    }
                }

                clientStatuses.web?.let { webStatus ->
                    webStatus.getDrawable(web)?.apply { statusView.setImageDrawable(this) }
                }*/
            })
        else if (settings.getBool("filledColors", false))
            unpatchStatusView = patcher.patch(m, Hook {
                val presence = it.args[0] as Presence? ?: return@Hook
                presence.status?.getDrawable(filled)?.apply { (it.thisObject as StatusView).setImageDrawable(clone()) }
            })
        else if (settings.exists("colorOnline") || settings.exists("colorIdle") || settings.exists("colorDND")) {
            val drawables = arrayOf(
                res.getDrawable(R.e.ic_status_online_16dp, null).clone().apply {
                    setStatusTint("colorOnline", res, R.c.status_green_600)
                },
                res.getDrawable(R.e.ic_status_idle_16dp, null).clone().apply {
                    setStatusTint("colorIdle", res, R.c.status_yellow)
                },
                res.getDrawable(R.e.ic_status_dnd_16dp, null).clone().apply {
                    setStatusTint("colorDND", res, R.c.status_red)
                }
            )
            val mobileIcon = res.getDrawable(R.e.ic_mobile, null).clone().apply {
                setStatusTint("colorOnline", res, R.c.status_green_600)
            }
            unpatchStatusView = patcher.patch(m, Hook {
                val presence = it.args[0] as Presence? ?: return@Hook
                val drawable =
                    presence.clientStatuses?.let { clientStatuses -> if (PresenceUtils.INSTANCE.isMobile(clientStatuses)) mobileIcon else null }
                        ?: presence.status?.getDrawable(drawables)
                if (drawable != null) (it.thisObject as StatusView).setImageDrawable(drawable.clone())
            })
        }
    }

    private fun patchMembersList(square: Boolean) {
        val avatarId = Utils.getResId("channel_members_list_item_avatar", "id")
        val memberViewHolder = ChannelMembersListViewHolderMember::class.java
        val memberViewHolderBinding = memberViewHolder.getDeclaredField("binding").apply { isAccessible = true }
        patcher.patch(
            memberViewHolder.getDeclaredMethod("bind", ChannelMembersListAdapter.Item.Member::class.java, Function0::class.java),
            Hook {
                val presence = (it.args[0] as ChannelMembersListAdapter.Item.Member).presence
                val binding = memberViewHolderBinding[it.thisObject] as WidgetChannelMembersListItemUserBinding

                presence?.clientStatuses?.let { clientStatuses ->
                    val usernameText = binding.a.findViewById<SimpleDraweeSpanTextView?>(usernameTextId) ?: return@let
                    addIndicators(usernameText, clientStatuses, settings.getInt("sizeMembersListInd", 24))
                }

                if (settings.radialStatusMembersList)
                    setRadialStatus(presence?.status, binding.a.findViewById(avatarId) ?: return@Hook, square)
            }
        )
    }

    private fun patchDMsList(square: Boolean) {
        val id = Utils.getResId("channels_list_item_private_name", "id")
        val avatarId = Utils.getResId("channels_list_item_private_avatar", "id")
        val channelPrivateItem = WidgetChannelsListAdapter.ItemChannelPrivate::class.java
        val channelPrivateItemBinding = channelPrivateItem.getDeclaredField("binding").apply { isAccessible = true }
        patcher.patch(
            channelPrivateItem.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, ChannelListItem::class.java),
            Hook {
                val presence = (it.args[1] as ChannelListItemPrivate).presence
                val binding = channelPrivateItemBinding[it.thisObject] as WidgetChannelsListItemChannelPrivateBinding

                presence?.clientStatuses?.let { clientStatuses ->
                    addIndicators(binding.a.findViewById(id), clientStatuses, settings.getInt("sizeDMsInd", 24))
                }

                if (settings.radialStatusDMs)
                    setRadialStatus(presence?.status, binding.a.findViewById(avatarId), square)
            }
        )
    }

    private fun patchFriendsList(square: Boolean) {
        val id = Utils.getResId("friends_list_item_name", "id")
        val avatarId = Utils.getResId("friends_list_item_avatar", "id")

        val itemUser = WidgetFriendsListAdapter.ItemUser::class.java
        val itemUserBinding = itemUser.getDeclaredField("binding").apply { isAccessible = true }
        val int = Int::class.javaPrimitiveType
        val item = FriendsListViewModel.Item::class.java
        patcher.patch(itemUser.getDeclaredMethod("onConfigure", int, item), Hook {
            val presence = (it.args[1] as FriendsListViewModel.Item.Friend).presence
            val binding = itemUserBinding[it.thisObject] as WidgetFriendsListAdapterItemFriendBinding

            presence?.clientStatuses?.let { clientStatuses ->
                addIndicators(binding.a.findViewById(id), clientStatuses, settings.getInt("sizeFriendsListInd", 24))
            }

            if (settings.radialStatusFriendsList)
                setRadialStatus(presence?.status, binding.a.findViewById(avatarId), square)
        })

        val itemPending = WidgetFriendsListAdapter.ItemPendingUser::class.java
        val itemPendingBinding = itemPending.getDeclaredField("binding").apply { isAccessible = true }
        patcher.patch(itemPending.getDeclaredMethod("onConfigure", int, item), Hook {
            val presence = (it.args[1] as FriendsListViewModel.Item.PendingFriendRequest).presence
            val binding = itemPendingBinding[it.thisObject] as WidgetFriendsListAdapterItemPendingBinding

            presence?.clientStatuses?.let { clientStatuses ->
                addIndicators(binding.a.findViewById(id), clientStatuses, settings.getInt("sizeFriendsListInd", 24))
            }

            if (settings.radialStatusFriendsList)
                setRadialStatus(presence?.status, binding.a.findViewById(avatarId), square)
        })
    }

    private var unpatchChatStatus: Runnable? = null
    fun patchChatStatus() {
        unpatchChatStatus?.run()
        if (!settings.getBool("chatStatus", true)) return

        val itemMessage = WidgetChatListAdapterItemMessage::class.java
        val itemNameField = itemMessage.getDeclaredField("itemName").apply { isAccessible = true }
        unpatchChatStatus = patcher.patch(
            itemMessage.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, ChatListEntry::class.java),
            Hook {
                val itemName = itemNameField[it.thisObject] as TextView? ?: return@Hook
                val entry = it.args[1] as MessageEntry

                val presence = entry.message.author.presence ?: return@Hook
                presence.status?.getDrawable(filled)?.apply {
                    itemName.appendIcon(this, settings.getInt("sizeChatStatus", 16))
                }
            }
        )
    }

    private var unpatchChatStatusPlatforms: Runnable? = null
    fun patchChatStatusPlatforms() {
        unpatchChatStatusPlatforms?.run()
        if (!settings.getBool("chatStatusPlatforms", false)) return

        val itemMessage = WidgetChatListAdapterItemMessage::class.java
        val itemNameField = itemMessage.getDeclaredField("itemName").apply { isAccessible = true }
        unpatchChatStatusPlatforms = patcher.patch(
            itemMessage.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, ChatListEntry::class.java),
            Hook {
                val itemName = itemNameField[it.thisObject] as TextView? ?: return@Hook
                val entry = it.args[1] as MessageEntry

                val presence = entry.message.author.presence ?: return@Hook
                addIndicators(
                    itemName,
                    presence.clientStatuses ?: return@Hook,
                    settings.getInt("sizeChatStatusPlatform", 24),
                    true
                )
            }
        )
    }

    private var unpatchAvatarPresenceView: Runnable? = null
    private var unpatchChatRadialStatus: Runnable? = null
    fun patchRadialStatus(radialStatus: Boolean, square: Boolean) {
        unpatchAvatarPresenceView?.run()
        unpatchChatRadialStatus?.run()
        if (!radialStatus) return

        // User profile
        if (settings.getBool("radialStatusUserProfile", true)) {
            val avatarCutoutId = Utils.getResId("avatar_cutout", "id")
            unpatchAvatarPresenceView = patcher.patch(
                UserAvatarPresenceView::class.java.getDeclaredMethod("a", UserAvatarPresenceView.a::class.java),
                Hook {
                    val data = it.args[0] as UserAvatarPresenceView.a
                    val status = data.b?.status

                    val avatarView = (it.thisObject as View).findViewById<View>(avatarCutoutId)
                    setRadialStatus(status, avatarView)
                }
            )
        }

        // Chat
        if (settings.getBool("radialStatusChat", true)) {
            val itemMessage = WidgetChatListAdapterItemMessage::class.java
            val itemAvatarField = itemMessage.getDeclaredField("itemAvatar").apply { isAccessible = true }
            unpatchChatRadialStatus = patcher.patch(
                itemMessage.getDeclaredMethod("onConfigure", Int::class.javaPrimitiveType, ChatListEntry::class.java),
                Hook {
                    val itemAvatar = itemAvatarField[it.thisObject] as View? ?: return@Hook
                    val entry = it.args[1] as MessageEntry

                    setRadialStatus(entry.message.author.presence?.status, itemAvatar, square)
                }
            )
        }
    }

    private fun setRadialStatus(status: ClientStatus?, avatarView: View, square: Boolean = false) {
        val failed = status?.getDrawable(if (square) radialDrawablesRect else radialDrawables)?.run {
            avatarView.setPadding(8, 8, 8, 8)
            avatarView.background = clone()
            false
        } ?: true
        if (failed) avatarView.apply {
            setPadding(0, 0, 0, 0)
            background = null
        }
    }
}
