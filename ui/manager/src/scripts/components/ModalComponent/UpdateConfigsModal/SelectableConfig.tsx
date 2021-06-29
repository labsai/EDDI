import * as moment from 'moment';
import * as React from 'react';
import { compose, pure, setDisplayName } from 'recompose';
import { BLUE_COLOR } from '../../../../styles/DefaultStylingProperties';
import TruncateTextComponent from '../../Assets/TruncateTextComponent';
import { IDetailedDescriptor } from '../../utils/AxiosFunctions';
import useStyles from '../AddPackagesModal/Package.styles';

interface IProps {
  descriptor: IDetailedDescriptor;
  selected: boolean;
  handleClick(resource: string): void;
}

const SelectableConfig = (props: IProps) => {
  const classes = useStyles();

  const handleClick = () => {
    props.handleClick(props.descriptor.resource);
  };

  return (
    <div>
      <div className={classes.content}>
        <div className={classes.topContent}>
          <button
            onClick={handleClick}
            style={{
              backgroundColor: props.selected ? '#4BCA81' : undefined,
            }}
            className={classes.button}>{`${
            props.selected ? '\u2714' : '+'
          }`}</button>
          <div
            style={{ color: props.selected ? BLUE_COLOR : undefined }}
            className={classes.packageName}>
            {props.descriptor.name}
          </div>
          <div className={classes.versionName}>
            {`V${props.descriptor.version}`}
          </div>
          <div className={classes.centerFlex} />
          <div className={classes.modifiedDate}>
            {moment(props.descriptor.lastModifiedOn).format('DD.MM.YYYY')}
          </div>
        </div>
        <div className={classes.bottomContent}>
          <TruncateTextComponent
            text={props.descriptor.description}
            length={80}
          />
        </div>
      </div>
    </div>
  );
};

const ComposedSelectableConfig: React.ComponentClass<IProps> = compose<
  IProps,
  IProps
>(
  pure,
  setDisplayName('SelectableConfig'),
)(SelectableConfig);

export default ComposedSelectableConfig;
