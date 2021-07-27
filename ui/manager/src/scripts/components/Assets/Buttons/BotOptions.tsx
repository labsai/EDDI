import IconButton from '@material-ui/core/IconButton';
import ListItemIcon from '@material-ui/core/ListItemIcon';
import Menu from '@material-ui/core/Menu';
import MenuItem from '@material-ui/core/MenuItem';
import { makeStyles } from '@material-ui/core/styles';
import Typography from '@material-ui/core/Typography';
import ArrowRightOutlinedIcon from '@material-ui/icons/ArrowRightOutlined';
import LogsIcon from '@material-ui/icons/Dns';
import ImportExportIcon from '@material-ui/icons/ImportExport';
import ChatBubbleIcon from '@material-ui/icons/ChatBubble';
import CloudDownloadOutlinedIcon from '@material-ui/icons/CloudDownloadOutlined';
import CreateIcon from '@material-ui/icons/Create';
import CreateOutlinedIcon from '@material-ui/icons/CreateOutlined';
import FileCopyIcon from '@material-ui/icons/FileCopy';
import FileCopyOutlinedIcon from '@material-ui/icons/FileCopyOutlined';
import ForumOutlinedIcon from '@material-ui/icons/ForumOutlined';
import HighlightOffOutlinedIcon from '@material-ui/icons/HighlightOffOutlined';
import MoreVertIcon from '@material-ui/icons/MoreVert';
import * as React from 'react';
import { connect, useDispatch } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import { openChatAction } from '../../../actions/ChatActions';
import { historyPush } from '../../../history';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import { IBot } from '../../utils/AxiosFunctions';
import { NOT_FOUND, READY } from '../../utils/helpers/BotHelper';
import { WHITE_COLOR } from '../../../../styles/DefaultStylingProperties';

interface IPublicProps {
  bot: IBot;
  apiUrl: string;
  openBotLogs?: () => void;
  exportBot?: () => void;
}

interface IPrivateProps extends IPublicProps {
  readOnly: boolean;
}

const ITEM_HEIGHT = 48;

const useStyles = makeStyles({
  menu: {
    '& .MuiListItemIcon-root': {
      minWidth: 20,
    },
  },
  submenu: {
    justifyContent: 'space-between',

    '& .MuiListItemIcon-root': {
      justifyContent: 'flex-end',
    },
  },
  moreIcon: {
    '& svg': {
      color: WHITE_COLOR,
    },
  },
});

const BotOptions = ({
  bot,
  readOnly,
  apiUrl,
  openBotLogs,
  exportBot,
}: IPrivateProps) => {
  const classes = useStyles();
  const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);
  const [anchorSubEl, setAnchorSubEl] =
    React.useState<null | HTMLElement>(null);

  const open = Boolean(anchorEl);
  const submenuOpen = Boolean(anchorSubEl);

  const handleClick = (event: React.MouseEvent<HTMLElement>) => {
    setAnchorEl(event.currentTarget);
  };

  const handleSubClick = (event: React.MouseEvent<HTMLElement>) => {
    setAnchorSubEl(Boolean(anchorSubEl) ? null : event.currentTarget);
  };

  const handleClose = () => {
    setAnchorEl(null);
    setAnchorSubEl(null);
  };

  const dispatch = useDispatch();

  const botDeployed = bot.deploymentStatus === READY;
  const botUndeployed = bot.deploymentStatus === NOT_FOUND;
  const isCurrentVersion = bot.version === bot.currentVersion;
  return (
    <div>
      <IconButton
        aria-label="more"
        aria-controls="long-menu"
        aria-haspopup="true"
        className={classes.moreIcon}
        onClick={handleClick}>
        <MoreVertIcon fontSize="large" />
      </IconButton>
      <Menu
        id="long-menu"
        anchorEl={anchorEl}
        keepMounted
        open={open}
        className={classes.menu}
        onClose={handleClose}
        PaperProps={{
          style: {
            maxHeight: ITEM_HEIGHT * 4.5,
            width: '20ch',
          },
        }}>
        <MenuItem
          key={'Open Chat'}
          disabled={!botDeployed}
          onClick={() => {
            handleClose();
            dispatch(openChatAction());
            historyPush(location.pathname, [`botId=${bot.id}`]);
            /* window
              .open(`${apiUrl}/chat/unrestricted/${bot.id}`, '_blank')
              .focus(); */
          }}>
          <ListItemIcon>
            <ChatBubbleIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'Open Chat'}</Typography>
        </MenuItem>
        <MenuItem
          key={'View Conversations'}
          onClick={() => {
            handleClose();
            eddiApiActionDispatchers.fetchConversationsAction(
              20,
              0,
              null,
              bot.resource,
            );
            modalActionDispatchers.showConversationsModal(bot);
          }}>
          <ListItemIcon>
            <ForumOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'View Conversations'}</Typography>
        </MenuItem>
        <MenuItem
          key={'View Logs'}
          onClick={() => {
            openBotLogs();
            handleClose();
          }}>
          <ListItemIcon>
            <LogsIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'View Logs'}</Typography>
        </MenuItem>
        <MenuItem
          key={'Export Bot'}
          onClick={() => {
            exportBot();
            handleClose();
          }}>
          <ListItemIcon>
            <ImportExportIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'Export Bot'}</Typography>
        </MenuItem>
        <MenuItem
          key={'Deploy'}
          disabled={(!botDeployed && !botUndeployed) || readOnly}
          onClick={() => {
            handleClose();
            if (
              (botUndeployed &&
                eddiApiActionDispatchers.deployBotAction(bot.resource)) ||
              botDeployed
            ) {
              modalActionDispatchers.showConfirmationModal(
                `Are you sure you want to undeploy ${bot.name}?`,
                null,
                () => eddiApiActionDispatchers.undeployBotAction(bot.resource),
              );
            }
          }}>
          <ListItemIcon>
            <CloudDownloadOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">
            {botDeployed ? 'Undeploy' : 'Deploy'}
          </Typography>
        </MenuItem>
        <MenuItem
          key={'Rename'}
          disabled={!isCurrentVersion || readOnly}
          onClick={() => {
            handleClose();
            modalActionDispatchers.showEditDescriptorModalAction(bot);
          }}>
          <ListItemIcon>
            <CreateIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'Rename'}</Typography>
        </MenuItem>
        <MenuItem
          key={'Edit JSON'}
          disabled={!isCurrentVersion || readOnly}
          onClick={() => {
            handleClose();
            modalActionDispatchers.showEditJsonModal(
              bot.resource,
              JSON.stringify(
                {
                  packages: bot.packages,
                  channels: bot.channels,
                },
                null,
                '\t',
              ),
            );
          }}>
          <ListItemIcon>
            <CreateOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'Edit JSON'}</Typography>
        </MenuItem>
        <MenuItem
          key={'Duplicate'}
          disabled={readOnly}
          className={classes.submenu}
          onClick={handleSubClick}>
          <Typography variant="inherit">{'Duplicate'}</Typography>
          <ListItemIcon>
            <ArrowRightOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <Menu
            id="long-submenu"
            anchorEl={anchorSubEl}
            keepMounted
            open={submenuOpen}
            onClose={handleClose}
            className={classes.menu}
            anchorOrigin={{
              vertical: 'top',
              horizontal: 'right',
            }}
            transformOrigin={{
              vertical: 'top',
              horizontal: 'left',
            }}
            PaperProps={{
              style: {
                maxHeight: ITEM_HEIGHT * 4.5,
                width: '20ch',
              },
            }}>
            <MenuItem
              key={'Normal'}
              disabled={readOnly}
              onClick={() => {
                handleClose();
                eddiApiActionDispatchers.duplicateAction(bot.resource, true);
              }}>
              <ListItemIcon>
                <FileCopyOutlinedIcon fontSize="small" />
              </ListItemIcon>
              <Typography variant="inherit">{'Normal'}</Typography>
            </MenuItem>
            <MenuItem
              key={'Deep copy'}
              disabled={readOnly}
              onClick={() => {
                handleClose();
                eddiApiActionDispatchers.duplicateAction(bot.resource, true);
              }}>
              <ListItemIcon>
                <FileCopyIcon fontSize="small" />
              </ListItemIcon>
              <Typography variant="inherit">{'Deep copy'}</Typography>
            </MenuItem>
            <MenuItem
              key={'Delete'}
              disabled={true}
              onClick={() => {
                handleClose();
              }}>
              <ListItemIcon>
                <HighlightOffOutlinedIcon fontSize="small" />
              </ListItemIcon>
              <Typography variant="inherit">{'Delete'}</Typography>
            </MenuItem>
          </Menu>
        </MenuItem>
      </Menu>
    </div>
  );
};

const ComposedBotOptions: React.ComponentClass<IPublicProps, IPrivateProps> =
  compose<IPrivateProps, IPublicProps>(
    pure,
    connect(readOnlySelector),
    setDisplayName('BotOptions'),
  )(BotOptions);

export default ComposedBotOptions;
