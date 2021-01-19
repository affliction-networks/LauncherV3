/**
 * This file is part of Technic Launcher Core.
 * Copyright ©2015 Syndicate, LLC
 *
 * Technic Launcher Core is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Technic Launcher Core is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License,
 * as well as a copy of the GNU Lesser General Public License,
 * along with Technic Launcher Core.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.technicpack.solder;

import net.technicpack.launcher.LauncherMain;
import net.technicpack.launchercore.modpacks.sources.IPackSource;
import net.technicpack.rest.RestObject;
import net.technicpack.rest.RestfulAPIException;
import net.technicpack.rest.io.PackInfo;
import net.technicpack.solder.io.FullModpacks;
import net.technicpack.solder.io.SolderPackInfo;
import net.technicpack.utilslib.Utils;
import org.joda.time.DateTime;
import org.joda.time.Seconds;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Level;

public class SolderPackSource implements IPackSource {
    private String baseUrl;
    private ISolderApi solder;

    public SolderPackSource(String baseUrl, ISolderApi solder) {
        this.baseUrl = baseUrl;
        this.solder = solder;
    }

    int cacheInSeconds = 60;
    Collection<PackInfo> cachedPublicPacks = null;
    DateTime lastSolderPull = new DateTime(0);

    public Collection<PackInfo> internalPublicPacks() throws RestfulAPIException {
        if (Seconds.secondsBetween(lastSolderPull, DateTime.now()).isLessThan(Seconds.seconds(cacheInSeconds))) {
            if (cachedPublicPacks != null)
                return cachedPublicPacks;
        }

        if (Seconds.secondsBetween(lastSolderPull, DateTime.now()).isLessThan(Seconds.seconds(cacheInSeconds / 10)))
            return new ArrayList<PackInfo>(0);

        try {
            LinkedList<PackInfo> allPackApis = new LinkedList<PackInfo>();

            String allPacksUrl = "http://bang.banghowdy.com/mainmodpacks.php?cid=" + LauncherMain.settingsInstance.getClientId();

            FullModpacks technic = RestObject.getRestObject(FullModpacks.class, allPacksUrl);
            for (PackInfo info : technic.getModpacks().values()) {
                allPackApis.add(info);
            }
            cachedPublicPacks = allPackApis;
            return cachedPublicPacks;
        } finally {
            lastSolderPull = DateTime.now();
        }
    }

    @Override
    public String getSourceName() {
        return "Public packs for solder " + baseUrl;
    }

    @Override
    public Collection<PackInfo> getPublicPacks() {
        try {
            return internalPublicPacks();
        } catch (RestfulAPIException e) {
            System.exit(1);
            return null;
        }
    }

    @Override
    public int getPriority(PackInfo pack) {
        return -1;
    }
}
