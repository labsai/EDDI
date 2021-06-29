import IconButton from '@material-ui/core/IconButton';
import ListItemIcon from '@material-ui/core/ListItemIcon';
import Menu from '@material-ui/core/Menu';
import MenuItem from '@material-ui/core/MenuItem';
import { makeStyles } from '@material-ui/core/styles';
import Typography from '@material-ui/core/Typography';
import ArrowRightOutlinedIcon from '@material-ui/icons/ArrowRightOutlined';
import CreateIcon from '@material-ui/icons/Create';
import CreateOutlinedIcon from '@material-ui/icons/CreateOutlined';
import FileCopyIcon from '@material-ui/icons/FileCopy';
import FileCopyOutlinedIcon from '@material-ui/icons/FileCopyOutlined';
import HighlightOffOutlinedIcon from '@material-ui/icons/HighlightOffOutlined';
import MoreVertIcon from '@material-ui/icons/MoreVert';
import * as React from 'react';
import { connect } from 'react-redux';
import { compose, pure, setDisplayName } from 'recompose';
import {
  BLUE_COLOR,
  WHITE_COLOR,
} from '../../../../styles/DefaultStylingProperties';
import eddiApiActionDispatchers from '../../../actions/EddiApiActionDispatchers';
import modalActionDispatchers from '../../../actions/ModalActionDispatchers';
import { readOnlySelector } from '../../../selectors/AuthenticationSelectors';
import { getTypeFromResource } from '../../utils/ApiFunctions';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import { PACKAGE } from '../../utils/EddiTypes';

interface IPublicProps {
  descriptor: IDetailedDescriptor;
  data: string | string[];
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
  optionButton: {
    alignContent: 'center',

    '&:hover svg': {
      color: BLUE_COLOR,
    },

    '& svg': {
      color: WHITE_COLOR,
    },
  },
});

const Options = ({ descriptor, readOnly, data }: IPrivateProps) => {
  const classes = useStyles();
  const [anchorEl, setAnchorEl] = React.useState<null | HTMLElement>(null);
  const [anchorSubEl, setAnchorSubEl] =
    React.useState<null | HTMLElement>(null);

  const isPackage = getTypeFromResource(descriptor.resource) === PACKAGE;
  const open = Boolean(anchorEl);
  const submenuOpen = Boolean(anchorSubEl);

  const handleClick = (event: React.MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setAnchorEl(event.currentTarget);
    fetchData(isPackage);
  };

  const handleSubClick = (event: React.MouseEvent<HTMLElement>) => {
    event.stopPropagation();
    setAnchorSubEl(Boolean(anchorSubEl) ? null : event.currentTarget);
  };

  const handleClose = () => {
    setAnchorEl(null);
    setAnchorSubEl(null);
  };

  const fetchData = (isPackage: boolean) => {
    if (isPackage) {
      eddiApiActionDispatchers.fetchPackageDataAction(descriptor.resource);
    } else {
      eddiApiActionDispatchers.fetchPluginAction(descriptor.resource);
    }
  };

  const isCurrentVersion = descriptor.version === descriptor.currentVersion;

  return (
    <div className={classes.optionButton}>
      <IconButton
        aria-label="more"
        aria-controls="long-menu"
        aria-haspopup="true"
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
          key={'Rename'}
          disabled={!isCurrentVersion || readOnly}
          onClick={() => {
            handleClose();
            modalActionDispatchers.showEditDescriptorModalAction(descriptor);
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
            modalActionDispatchers.showEditJsonModal(descriptor.resource, data);
          }}>
          <ListItemIcon>
            <CreateOutlinedIcon fontSize="small" />
          </ListItemIcon>
          <Typography variant="inherit">{'Edit JSON'}</Typography>
        </MenuItem>
        {isPackage ? (
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
                  eddiApiActionDispatchers.duplicateAction(
                    descriptor.resource,
                    false,
                  );
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
                  eddiApiActionDispatchers.duplicateAction(
                    descriptor.resource,
                    true,
                  );
                }}>
                <ListItemIcon>
                  <FileCopyIcon fontSize="small" />
                </ListItemIcon>
                <Typography variant="inherit">{'Deep copy'}</Typography>
              </MenuItem>
            </Menu>
          </MenuItem>
        ) : (
          <MenuItem
            key={'Duplicate'}
            disabled={readOnly}
            onClick={() => {
              handleClose();
              eddiApiActionDispatchers.duplicateAction(
                descriptor.resource,
                false,
              );
            }}>
            <ListItemIcon>
              <FileCopyOutlinedIcon fontSize="small" />
            </ListItemIcon>
            <Typography variant="inherit">{'Duplicate'}</Typography>
          </MenuItem>
        )}
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
    </div>
  );
};

const ComposedOptions: React.ComponentClass<IPublicProps> = compose<
  IPrivateProps,
  IPublicProps
>(
  pure,
  connect(readOnlySelector),
  setDisplayName('Options'),
)(Options);

export default ComposedOptions;
