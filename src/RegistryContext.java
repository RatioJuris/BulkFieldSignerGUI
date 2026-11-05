// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 PrajnaBytes International Private Limited | RatioJuris
import java.util.List;

public record RegistryContext(List<SignerIdentity> signers) {
    public RegistryContext { signers = List.copyOf(signers); }
}
