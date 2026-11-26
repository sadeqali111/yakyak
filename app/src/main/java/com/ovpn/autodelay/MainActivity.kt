package com.ovpn.autodelay

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ovpn.autodelay.databinding.ActivityMainBinding
import com.ovpn.autodelay.databinding.ItemAccountBinding
import com.ovpn.autodelay.databinding.ItemServerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var serverAdapter: ServerAdapter
    private lateinit var accountAdapter: AccountAdapter
    private var accounts = mutableListOf<VpnAccount>()
    private var pendingAction: (() -> Unit)? = null

    private val zipPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val list = contentResolver.openInputStream(uri)?.use { stream ->
                    OvpnConfigParser.saveZip(this@MainActivity, stream)
                } ?: emptyList()
                VpnStateBus.loadedProfiles = list
                withContext(Dispatchers.Main) {
                    VpnStateBus.notifyListChanged()
                    Toast.makeText(this@MainActivity, "Loaded ${list.size} servers!", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupAdapters()
        loadInitialData()
        setupClicks()
        observeState()
    }

    private fun setupAdapters() {
        serverAdapter = ServerAdapter { profile ->
            VpnStateBus.isFastestMode.value = false
            VpnStateBus.selectedProfileName.value = profile.name
            VpnStateBus.activeProfile.value = profile
            VpnStateBus.notifyListChanged()
            binding.bottomNav.selectedItemId = R.id.nav_home
            startVpnAction {
                val i = Intent(this, AutoDelayVpnService::class.java)
                    .setAction(AutoDelayVpnService.ACTION_CONNECT_SINGLE)
                    .putExtra(AutoDelayVpnService.EXTRA_PROFILE_NAME, profile.name)
                ContextCompat.startForegroundService(this, i)
            }
        }
        binding.rvServers.layoutManager = LinearLayoutManager(this)
        binding.rvServers.adapter = serverAdapter

        accountAdapter = AccountAdapter(
            onActivate = { acc ->
                accounts = accounts.map { it.copy(active = it.id == acc.id) }.toMutableList()
                AccountStore.save(this, accounts)
                syncActiveAccount()
            },
            onDelete = { acc ->
                accounts.removeAll { it.id == acc.id }
                if (accounts.none { it.active } && accounts.isNotEmpty()) {
                    accounts[0] = accounts[0].copy(active = true)
                }
                AccountStore.save(this, accounts)
                syncActiveAccount()
            }
        )
        binding.rvAccounts.layoutManager = LinearLayoutManager(this)
        binding.rvAccounts.adapter = accountAdapter
    }

    private fun loadInitialData() {
        accounts = AccountStore.load(this)
        syncActiveAccount()

        lifecycleScope.launch(Dispatchers.IO) {
            if (VpnStateBus.loadedProfiles.isEmpty()) {
                VpnStateBus.loadedProfiles = OvpnConfigParser.loadPersistedOrBundled(this@MainActivity)
            }
            withContext(Dispatchers.Main) {
                VpnStateBus.notifyListChanged()
            }
        }
    }

    private fun syncActiveAccount() {
        val active = accounts.firstOrNull { it.active } ?: accounts.firstOrNull()
        VpnStateBus.username = active?.username ?: ""
        VpnStateBus.password = active?.password ?: ""
        binding.tvHomeAccountName.text = active?.username ?: "Tap to add account"
        binding.tvAccountsEmpty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        accountAdapter.submit(accounts)
    }

    private fun setupClicks() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.tabHome.visibility = if (item.itemId == R.id.nav_home) View.VISIBLE else View.GONE
            binding.tabServers.visibility = if (item.itemId == R.id.nav_servers) View.VISIBLE else View.GONE
            binding.tabAccounts.visibility = if (item.itemId == R.id.nav_accounts) View.VISIBLE else View.GONE
            true
        }

        binding.cardLocation.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_servers
        }

        binding.cardAccount.setOnClickListener {
            binding.bottomNav.selectedItemId = R.id.nav_accounts
        }

        binding.btnImportZip.setOnClickListener {
            zipPickerLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        binding.btnAddAccount.setOnClickListener { showAddAccountDialog() }

        binding.cardFastestOption.setOnClickListener {
            VpnStateBus.isFastestMode.value = true
            VpnStateBus.selectedProfileName.value = null
            VpnStateBus.notifyListChanged()
            binding.bottomNav.selectedItemId = R.id.nav_home
            startVpnAction {
                val i = Intent(this, AutoDelayVpnService::class.java)
                    .setAction(AutoDelayVpnService.ACTION_CONNECT_FASTEST)
                ContextCompat.startForegroundService(this, i)
            }
        }

        binding.btnFastScan.setOnClickListener {
            startVpnAction {
                val i = Intent(this, AutoDelayVpnService::class.java)
                    .setAction(AutoDelayVpnService.ACTION_FAST_SCAN_ONLY)
                ContextCompat.startForegroundService(this, i)
            }
        }

        binding.btnPower.setOnClickListener {
            when (VpnStateBus.connState.value) {
                ConnState.CONNECTED, ConnState.CONNECTING, ConnState.SCANNING -> {
                    val i = Intent(this, AutoDelayVpnService::class.java).setAction(AutoDelayVpnService.ACTION_STOP)
                    startService(i)
                }
                ConnState.DISCONNECTED -> {
                    startVpnAction {
                        val selected = VpnStateBus.selectedProfileName.value
                        val i = if (VpnStateBus.isFastestMode.value || selected == null) {
                            Intent(this, AutoDelayVpnService::class.java).setAction(AutoDelayVpnService.ACTION_CONNECT_FASTEST)
                        } else {
                            Intent(this, AutoDelayVpnService::class.java)
                                .setAction(AutoDelayVpnService.ACTION_CONNECT_SINGLE)
                                .putExtra(AutoDelayVpnService.EXTRA_PROFILE_NAME, selected)
                        }
                        ContextCompat.startForegroundService(this, i)
                    }
                }
            }
        }
    }

    private fun startVpnAction(action: () -> Unit) {
        if (VpnStateBus.loadedProfiles.isEmpty()) {
            Toast.makeText(this, "Import your servers ZIP first!", Toast.LENGTH_SHORT).show()
            binding.bottomNav.selectedItemId = R.id.nav_servers
            return
        }
        if (VpnStateBus.username.isEmpty() || VpnStateBus.password.isEmpty()) {
            Toast.makeText(this, "Add an OpenVPN account first!", Toast.LENGTH_SHORT).show()
            binding.bottomNav.selectedItemId = R.id.nav_accounts
            return
        }
        val perm = VpnService.prepare(this)
        if (perm != null) {
            pendingAction = action
            vpnPermissionLauncher.launch(perm)
        } else {
            action()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            VpnStateBus.connState.collect { st ->
                when (st) {
                    ConnState.DISCONNECTED -> {
                        binding.btnPower.setBackgroundResource(R.drawable.bg_power_button_disconnected)
                        binding.tvConnectionState.text = "Disconnected"
                        binding.tvConnectionState.setTextColor(getColor(R.color.text_primary))
                        binding.tvHomeLocationLabel.text = "Selected location"
                    }
                    ConnState.SCANNING -> {
                        binding.btnPower.setBackgroundResource(R.drawable.bg_power_button_connecting)
                        binding.tvConnectionState.text = "Fast Scanning..."
                        binding.tvConnectionState.setTextColor(getColor(R.color.accent_amber))
                    }
                    ConnState.CONNECTING -> {
                        binding.btnPower.setBackgroundResource(R.drawable.bg_power_button_connecting)
                        binding.tvConnectionState.text = "Connecting..."
                        binding.tvConnectionState.setTextColor(getColor(R.color.accent_amber))
                    }
                    ConnState.CONNECTED -> {
                        binding.btnPower.setBackgroundResource(R.drawable.bg_power_button_connected)
                        binding.tvConnectionState.text = "Connected"
                        binding.tvConnectionState.setTextColor(getColor(R.color.accent_green))
                        binding.tvHomeLocationLabel.text = "Connected to"
                    }
                }
            }
        }

        lifecycleScope.launch {
            VpnStateBus.statusSubtext.collect { txt ->
                binding.tvRealDelayPill.text = txt
                val isGreen = VpnStateBus.connState.value == ConnState.CONNECTED && txt.contains("ms")
                binding.tvRealDelayPill.setTextColor(
                    getColor(if (isGreen) R.color.accent_green else R.color.text_secondary)
                )
            }
        }

        lifecycleScope.launch {
            VpnStateBus.activeProfile.collect { prof ->
                if (prof != null) {
                    binding.tvHomeFlag.text = prof.flag
                    binding.tvHomeLocationName.text = prof.displayName
                } else {
                    binding.tvHomeFlag.text = "⚡"
                    binding.tvHomeLocationName.text = "Fastest (Auto)"
                }
            }
        }

        lifecycleScope.launch {
            VpnStateBus.scanHint.collect { binding.tvScanHint.text = it }
        }

        lifecycleScope.launch {
            VpnStateBus.listVersion.collect {
                val list = VpnStateBus.loadedProfiles
                binding.tvLocationsHeader.text = "Locations (${list.size})"
                binding.rbFastest.isChecked = VpnStateBus.isFastestMode.value
                serverAdapter.submit(list, VpnStateBus.selectedProfileName.value, VpnStateBus.isFastestMode.value)
            }
        }
    }

    private fun showAddAccountDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }
        val etUser = EditText(this).apply { hint = "Username / Email" }
        val etPass = EditText(this).apply {
            hint = "Password"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(etUser)
        layout.addView(etPass)

        AlertDialog.Builder(this)
            .setTitle("Add OpenVPN Account")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val u = etUser.text.toString().trim()
                val p = etPass.text.toString().trim()
                if (u.isNotEmpty() && p.isNotEmpty()) {
                    accounts = accounts.map { it.copy(active = false) }.toMutableList()
                    accounts.add(VpnAccount(UUID.randomUUID().toString(), u, p, true))
                    AccountStore.save(this, accounts)
                    syncActiveAccount()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private inner class ServerAdapter(
        private val onClick: (OvpnProfileItem) -> Unit
    ) : RecyclerView.Adapter<ServerAdapter.VH>() {

        private var items: List<OvpnProfileItem> = emptyList()
        private var selectedName: String? = null
        private var fastestMode: Boolean = true

        fun submit(list: List<OvpnProfileItem>, selected: String?, fastest: Boolean) {
            items = list.toList()
            selectedName = selected
            fastestMode = fastest
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemServerBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.b.tvFlag.text = item.flag
            holder.b.tvCountryName.text = item.displayName
            holder.b.tvHostSub.text = item.host
            holder.b.rbSelected.isChecked = !fastestMode && item.name == selectedName

            when {
                item.isTesting -> {
                    holder.b.tvPingBadge.text = "Testing..."
                    holder.b.tvPingBadge.setBackgroundResource(R.drawable.bg_pill_gray)
                    holder.b.tvPingBadge.setTextColor(getColor(R.color.accent_amber))
                }
                item.realDelayMs < Long.MAX_VALUE -> {
                    holder.b.tvPingBadge.text = "⚡ ${item.realDelayMs} ms"
                    holder.b.tvPingBadge.setBackgroundResource(R.drawable.bg_pill_green)
                    holder.b.tvPingBadge.setTextColor(getColor(R.color.accent_green))
                }
                item.tcpPingMs < Long.MAX_VALUE -> {
                    holder.b.tvPingBadge.text = "~${item.tcpPingMs} ms"
                    holder.b.tvPingBadge.setBackgroundResource(R.drawable.bg_pill_gray)
                    holder.b.tvPingBadge.setTextColor(getColor(R.color.accent_indigo))
                }
                else -> {
                    holder.b.tvPingBadge.text = "—"
                    holder.b.tvPingBadge.setBackgroundResource(R.drawable.bg_pill_gray)
                    holder.b.tvPingBadge.setTextColor(getColor(R.color.text_secondary))
                }
            }

            holder.b.root.setOnClickListener { onClick(item) }
        }
    }

    private inner class AccountAdapter(
        private val onActivate: (VpnAccount) -> Unit,
        private val onDelete: (VpnAccount) -> Unit
    ) : RecyclerView.Adapter<AccountAdapter.VH>() {

        private var items: List<VpnAccount> = emptyList()

        fun submit(list: List<VpnAccount>) {
            items = list.toList()
            notifyDataSetChanged()
        }

        inner class VH(val b: ItemAccountBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val b = ItemAccountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return VH(b)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val acc = items[position]
            holder.b.tvAccEmail.text = acc.username
            holder.b.swAccActive.setOnCheckedChangeListener(null)
            holder.b.swAccActive.isChecked = acc.active

            if (acc.active) {
                holder.b.accountCardRoot.setBackgroundResource(R.drawable.bg_card_active_border)
                holder.b.tvAccBadge.text = "⚡ Active"
                holder.b.tvAccBadge.setBackgroundResource(R.drawable.bg_pill_green)
                holder.b.tvAccBadge.setTextColor(getColor(R.color.accent_green))
            } else {
                holder.b.accountCardRoot.setBackgroundResource(R.drawable.bg_card_rounded)
                holder.b.tvAccBadge.text = "Ready"
                holder.b.tvAccBadge.setBackgroundResource(R.drawable.bg_pill_gray)
                holder.b.tvAccBadge.setTextColor(getColor(R.color.text_secondary))
            }

            holder.b.swAccActive.setOnCheckedChangeListener { _, checked ->
                if (checked) onActivate(acc)
            }
            holder.b.btnDeleteAcc.setOnClickListener { onDelete(acc) }
        }
    }
}
