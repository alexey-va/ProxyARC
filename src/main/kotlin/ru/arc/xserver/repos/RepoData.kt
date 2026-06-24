package ru.arc.xserver.repos

abstract class RepoData<SELF : RepoData<SELF>> {
    @Transient
    @JvmField
    var dirty: Boolean = true

    abstract fun id(): String

    abstract fun isRemove(): Boolean

    abstract fun merge(other: SELF)
}
