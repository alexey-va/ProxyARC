package ru.arc.xserver.repos;

import lombok.Data;

@Data
public abstract class RepoData<SELF extends RepoData<SELF>> {

    transient boolean dirty = true;

    public abstract String id();
    public abstract boolean isRemove();
    public abstract void merge(SELF other);
}
