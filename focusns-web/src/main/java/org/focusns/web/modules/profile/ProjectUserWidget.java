package org.focusns.web.modules.profile;

/*
 * #%L
 * FocusSNS Web
 * %%
 * Copyright (C) 2011 - 2013 FocusSNS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 2.1 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-2.1.html>.
 * #L%
 */

import org.focusns.common.exception.ServiceException;
import org.focusns.common.exception.ServiceExceptionCode;
import org.focusns.common.image.ImageUtils;
import org.focusns.common.image.Rectangle;
import org.focusns.common.lang.ObjectUtils;
import org.focusns.common.web.WebUtils;
import org.focusns.common.web.widget.annotation.bind.WidgetAttribute;
import org.focusns.common.web.widget.mvc.support.Navigator;
import org.focusns.model.core.Project;
import org.focusns.model.core.ProjectLink;
import org.focusns.model.core.ProjectUser;
import org.focusns.service.common.TempStorageService;
import org.focusns.service.common.helper.CoordinateHelper;
import org.focusns.service.core.ProjectLinkService;
import org.focusns.service.core.ProjectUserService;
import org.focusns.web.widget.constraint.annotation.RequiresProject;
import org.focusns.web.widget.constraint.annotation.RequiresProjectUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

@Controller
@RequestMapping("/project")
public class ProjectUserWidget {

    @Autowired@Qualifier("localTempStorageService")
    private TempStorageService storageService;
    @Autowired
    private ProjectUserService projectUserService;
    @Autowired
    private ProjectLinkService projectLinkService;

    @RequiresProject
    @RequiresProjectUser
    @RequestMapping(value="/user-edit", method = RequestMethod.GET)
    public String doEdit(@WidgetAttribute Project project, @WidgetAttribute ProjectUser projectUser, Model model) throws IOException {
        //
        Object[] avatarCoordinates = CoordinateHelper.getAvatarCoordinates(projectUser);
        if(storageService.checkTempResource(avatarCoordinates)>0) {
            model.addAttribute("tempExists", true);
        }
        //
        model.addAttribute("project", project);
        model.addAttribute("projectUser", projectUser);
        //
        return "modules/profile/user-edit";
    }

    @RequiresProject
    @RequiresProjectUser
    @RequestMapping("/user-view")
    public String doView(@WidgetAttribute(required = false) ProjectUser projectUser,
                         @WidgetAttribute Project project, Model model) {
        //
        ProjectUser dbUser = projectUserService.getProjectUser(project.getCreatedById());
        model.addAttribute("projectUser", dbUser);
        model.addAttribute("project", project);
        //
        if (projectUser != null) {
            ProjectLink projectLink = projectLinkService.getProjectLink(projectUser.getProjectId(), project.getId());
            model.addAttribute("projectLink", projectLink);
            model.addAttribute("fromProject", projectUser.getProject());
            model.addAttribute("toProject", project);
        }
        //
        return "modules/profile/user-view";
    }

    @RequestMapping(value = "/user-modify", method = RequestMethod.POST)
    public void doModify(ProjectUser target, @WidgetAttribute ProjectUser projectUser) {
        //
        ObjectUtils.extend(projectUser, target);
        //
        Navigator.get().withAttribute("projectUser", projectUser);
        if(StringUtils.hasText(target.getOldPassword()) && StringUtils.hasText(target.getNewPassword())) {
            Navigator.get().navigateTo("pwd-modified");
        } else {
            Navigator.get().navigateTo("user-modified");
        }
        projectUserService.modifyProjectUser(projectUser);
    }

    @ExceptionHandler(ServiceException.class)
    public void handleServiceException(ServiceException serviceException) {
        if(serviceException.getCode().getStatus() == ServiceExceptionCode.PASSWORD_MISS_MATCH.getStatus()) {
            Navigator.get().withAttribute("serviceException", serviceException).navigateTo("pwd-modify-failure");
        }
    }

    @RequestMapping("/user-avatar/download")
    public ResponseEntity<byte[]> doAvatar(@RequestParam Long userId, @RequestParam(required = false) Boolean temp,
            @RequestParam(required = false) Integer width, @RequestParam(required = false) Integer height, WebRequest webRequest) throws IOException {
        //
        boolean notModified = false;
        InputStream inputStream = null;
        //
        ProjectUser projectUser = projectUserService.getProjectUser(userId);
        Object[] avatarCoordinates = CoordinateHelper.getAvatarCoordinates(projectUser);
        if(temp!=null && temp.booleanValue()) {
            long lastModified = storageService.checkTempResource(avatarCoordinates);
            if(lastModified>0 && webRequest.checkNotModified(lastModified)) {
                notModified = true;
            } else {
                inputStream = storageService.loadTempResource(avatarCoordinates);
            }
        } else if(width==null || height==null) {
            long lastModified = storageService.checkResource(avatarCoordinates);
            if(lastModified>0 && webRequest.checkNotModified(lastModified)) {
                notModified = true;
            } else {
                inputStream = storageService.loadResource(avatarCoordinates);
            }
        } else {
            Object size = width + "x" + height;
            long lastModified = storageService.checkSizedResource(size, avatarCoordinates);
            if(lastModified>0 && webRequest.checkNotModified(lastModified)) {
                notModified = true;
            } else {
                inputStream = storageService.loadSizedResource(size, avatarCoordinates);
            }
        }
        //
        if(notModified) {
            return new ResponseEntity<byte[]>(HttpStatus.NOT_MODIFIED);
        }
        //
        return WebUtils.getResponseEntity(FileCopyUtils.copyToByteArray(inputStream), MediaType.IMAGE_PNG);
    }

    @RequestMapping("/user-avatar/upload")
    public void doUpload(@RequestParam Long projectId, @RequestParam Long userId, MultipartFile file)
            throws IOException {
        //
        ProjectUser projectUser = projectUserService.getProjectUser(userId);
        Object[] avatarCoordinates = CoordinateHelper.getAvatarCoordinates(projectUser);
        storageService.persistTempResource(file.getInputStream(), avatarCoordinates);
        //
        Navigator.get().navigateTo("avatar-uploaded");
    }

    @RequestMapping("/user-avatar/crop")
    public void doCrop(@RequestParam Long projectId, @RequestParam Long userId, Rectangle rectangle) throws IOException {
        //
        ProjectUser projectUser = projectUserService.getProjectUser(userId);
        Object[] avatarCoordinates = CoordinateHelper.getAvatarCoordinates(projectUser);
        //
        InputStream tempInputStream = storageService.loadTempResource(avatarCoordinates);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageUtils.crop(tempInputStream, baos, rectangle);
        // bridge from ByteArrayOutputStream
        InputStream targetInputStream = new ByteArrayInputStream(baos.toByteArray());
        storageService.persistResource(targetInputStream, avatarCoordinates);
        storageService.cleanSizedResource(avatarCoordinates);
        //
        Navigator.get().navigateTo("avatar-cropped");
    }
}
