import { CSSProperties } from 'react';
import {
  BLUE_COLOR,
  DARK_BLUE_COLOR,
  LARGE_FONT3,
  RED_COLOR,
  SMALL_FONT,
} from '../../../styles/DefaultStylingProperties';

const styles: { [key: string]: IExtendedCSSProperties } = {
  botHeader: {
    display: 'flex',
    flex: 1,
    height: '35px',
    marginTop: '47px',
  },
  botHeaderSpacing: {
    flexGrow: 1,
  },
  botName: {
    color: DARK_BLUE_COLOR,
    fontSize: LARGE_FONT3,
    marginRight: '20px',
    textAlign: 'left',
    maxWidth: '350px',
    overflow: 'hidden',
    whiteSpace: 'nowrap',
    textOverflow: 'ellipsis',
  },
  unpublishedChanges: {
    display: 'flex',
  },
  unpublishedChangesText: {
    color: RED_COLOR,
    fontSize: SMALL_FONT,
    marginLeft: '5px',
  },
  warningIcon: {
    height: '14px',
    marginLeft: '25px',
    marginTop: '9px',
  },
  button: {
    height: '35px',
    marginLeft: '10px',
    color: BLUE_COLOR,
    fontSize: SMALL_FONT,
    textAlign: 'center',
  },
  deployButton: {
    height: '35px',
    marginLeft: '10px',
  },
  options: {
    marginTop: 'auto',
    marginBottom: 'auto',
  },
};
export default styles;
