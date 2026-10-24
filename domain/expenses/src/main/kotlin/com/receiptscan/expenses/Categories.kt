package com.receiptscan.expenses

/** Expense category. Built-in ids are stable and used by rules, aliases and voice commands. */
data class Category(val id: String, val name: String, val builtIn: Boolean = true)

object Categories {
    const val UNCATEGORIZED = "uncategorized"

    val builtIn: List<Category> = listOf(
        Category("groceries", "Groceries"),
        Category("dining", "Dining"),
        Category("fuel", "Fuel"),
        Category("transport", "Transport"),
        Category("health", "Health"),
        Category("shopping", "Shopping"),
        Category("electronics", "Electronics"),
        Category("utilities", "Utilities"),
        Category("bills", "Bills"),
        Category("household", "Household"),
        Category("personal-care", "Personal care"),
        Category("education", "Education"),
        Category("entertainment", "Entertainment"),
        Category("travel", "Travel"),
        Category("gifts", "Gifts"),
        Category(UNCATEGORIZED, "Uncategorized"),
    )

    private val byId = builtIn.associateBy { it.id }

    fun name(id: String): String = byId[id]?.name ?: id.replace('-', ' ').replaceFirstChar { it.uppercase() }

    fun isKnown(id: String) = byId.containsKey(id)
}
